#!/usr/bin/env python3
"""Build Tuikio's native executables for Android: the PTY helper and every game port.

Each output is a self-contained static-PIE ELF executable linked against musl libc with
Zig's cross toolchain. Android installs files named lib*.so from the APK into the app's
read-only nativeLibraryDir, which is the only location an app targeting API 29+ may
execute from (see docs/RUNTIME.md), so every executable is installed as lib<name>.so.

    python3 native/build.py                      # all ABIs, all games
    python3 native/build.py --abi x86_64 --game 2048
    ZIG=/path/to/zig python3 native/build.py

Outputs:  native/build/out/<abi>/lib<name>.so     (copied into the APK by the app build)
          native/build/out/manifest.json          (sizes + SHA-256 of every output)

Sources are downloaded once into native/.cache/dl and verified against
native/sources.lock.json before anything is unpacked. A mismatch aborts the build.
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".cache")
DL = os.path.join(CACHE, "dl")
BUILD = os.path.join(HERE, "build")
OUT = os.path.join(BUILD, "out")

ABIS = {
    "arm64-v8a": "aarch64-linux-musl",
    "armeabi-v7a": "arm-linux-musleabihf",
    "x86_64": "x86_64-linux-musl",
}

# Hardened, size-optimised static-PIE. 16 KB segment alignment keeps the binaries
# loadable on Android 15+ devices that use 16 KB memory pages.
CFLAGS = "-Os -fPIE -ffunction-sections -fdata-sections -fstack-protector-strong -Wno-error"
# Pre-C99 code in older games: keep their warnings, but do not let modern clang reject them.
LEGACY = ("-Wno-implicit-int -Wno-implicit-function-declaration -Wno-int-conversion "
          "-Wno-incompatible-pointer-types -Wno-return-type -Wno-deprecated-non-prototype")
LDFLAGS = "-static -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,-z,relro,-z,now -s"


def log(msg):
    print(f"[native] {msg}", flush=True)


def run(cmd, cwd, env=None, quiet=True, logname="build.log"):
    """Run a shell command; on failure print the tail of its log and abort."""
    path = os.path.join(cwd, logname)
    with open(path, "ab") as fh:
        fh.write(f"\n$ {cmd}\n".encode())
        fh.flush()
        proc = subprocess.run(cmd, shell=True, cwd=cwd, env=env,
                              stdout=fh if quiet else None, stderr=subprocess.STDOUT)
    if proc.returncode != 0:
        with open(path, "rb") as fh:
            tail = fh.read()[-4000:].decode(errors="replace")
        sys.exit(f"[native] FAILED in {cwd}: {cmd}\n{tail}")


# ---------------------------------------------------------------- sources

def lockfile():
    with open(os.path.join(HERE, "sources.lock.json")) as fh:
        return json.load(fh)["files"]


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch(name):
    entry = lockfile()[name]
    os.makedirs(DL, exist_ok=True)
    dst = os.path.join(DL, name)
    if not os.path.exists(dst):
        log(f"download {entry['url']}")
        tmp = dst + ".part"
        with urllib.request.urlopen(entry["url"], timeout=120) as r, open(tmp, "wb") as fh:
            shutil.copyfileobj(r, fh)
        os.replace(tmp, dst)
    got = sha256(dst)
    if got != entry["sha256"]:
        os.remove(dst)
        sys.exit(f"[native] checksum mismatch for {name}: expected {entry['sha256']}, got {got}")
    return dst


def unpack(name, dest):
    """Fresh, verified copy of a source tarball; returns the top-level directory."""
    archive = fetch(name)
    if os.path.exists(dest):
        shutil.rmtree(dest)
    os.makedirs(dest)
    with tarfile.open(archive) as tf:
        for m in tf.getmembers():
            target = os.path.realpath(os.path.join(dest, m.name))
            if not target.startswith(os.path.realpath(dest) + os.sep):
                sys.exit(f"[native] refusing path outside archive root: {m.name}")
            if m.issym() or m.islnk():
                link = os.path.realpath(os.path.join(os.path.dirname(target), m.linkname))
                if not link.startswith(os.path.realpath(dest) + os.sep):
                    sys.exit(f"[native] refusing link outside archive root: {m.name}")
        tf.extractall(dest, filter="data")
    tops = os.listdir(dest)
    return os.path.join(dest, tops[0]) if len(tops) == 1 else dest


def apply_patches(src, game):
    pdir = os.path.join(HERE, "ports", game, "patches")
    if not os.path.isdir(pdir):
        return
    for p in sorted(os.listdir(pdir)):
        if p.endswith(".patch"):
            run(f"patch -p1 --forward --batch < {os.path.join(pdir, p)}", src)


# ---------------------------------------------------------------- toolchain

def zig():
    z = os.environ.get("ZIG") or shutil.which("zig") or "/opt/tuikio-tools/zig/ziglang/zig"
    if not os.path.exists(z) and not shutil.which(z):
        sys.exit("[native] zig not found; set ZIG (see docs/BUILD.md)")
    return z


class Abi:
    def __init__(self, name):
        self.name = name
        self.triple = ABIS[name]
        self.root = os.path.join(BUILD, name)
        self.sysroot = os.path.join(self.root, "sysroot")
        self.bin = os.path.join(self.root, "bin")
        self.out = os.path.join(OUT, name)
        os.makedirs(self.bin, exist_ok=True)
        os.makedirs(self.out, exist_ok=True)
        z = zig()
        wrappers = {
            "cc": f'exec "{z}" cc -target {self.triple} "$@"',
            "c++": f'exec "{z}" c++ -target {self.triple} "$@"',
            "ar": f'exec "{z}" ar "$@"',
            "ranlib": f'exec "{z}" ranlib "$@"',
        }
        for tool, body in wrappers.items():
            path = os.path.join(self.bin, tool)
            with open(path, "w") as fh:
                fh.write("#!/bin/sh\n" + body + "\n")
            os.chmod(path, 0o755)
        self.cc = os.path.join(self.bin, "cc")
        self.cxx = os.path.join(self.bin, "c++")
        self.ar = os.path.join(self.bin, "ar")
        self.ranlib = os.path.join(self.bin, "ranlib")

    def work(self, game):
        d = os.path.join(self.root, "work", game)
        os.makedirs(d, exist_ok=True)
        return d

    def env(self, **extra):
        e = dict(os.environ)
        e.update(CC=self.cc, CXX=self.cxx, AR=self.ar, RANLIB=self.ranlib,
                 CFLAGS=CFLAGS, CXXFLAGS=CFLAGS, LDFLAGS=LDFLAGS,
                 CPPFLAGS=f"-I{self.sysroot}/include -I{self.sysroot}/include/ncursesw")
        e.update(extra)
        return e

    @property
    def curses_libs(self):
        return f"-L{self.sysroot}/lib -lncursesw"

    def install(self, built, name):
        dst = os.path.join(self.out, f"lib{name}.so")
        shutil.copyfile(built, dst)
        os.chmod(dst, 0o755)
        return dst


# ---------------------------------------------------------------- libraries

def build_ncurses(abi):
    stamp = os.path.join(abi.sysroot, "lib", "libncursesw.a")
    if os.path.exists(stamp):
        return
    log(f"{abi.name}: ncursesw")
    src = unpack("ncurses_6.4+20240113.orig.tar.gz", os.path.join(abi.work("ncurses"), "src"))
    b = os.path.join(abi.work("ncurses"), "obj")
    os.makedirs(b, exist_ok=True)
    # Terminal descriptions are compiled in (no terminfo database on Android).
    run(f"{src}/configure --host={abi.triple} --build=x86_64-pc-linux-gnu --prefix=/ "
        "--without-shared --with-normal --without-debug --without-ada --without-cxx "
        "--without-cxx-binding --without-manpages --without-progs --without-tests "
        "--enable-widec --disable-database --without-pkg-config --disable-home-terminfo "
        "--with-fallbacks=xterm-256color,xterm,vt100,linux,dumb --with-build-cc=gcc "
        "--disable-stripping --without-gpm --enable-ext-colors --enable-ext-mouse "
        "--with-default-terminfo-dir=/nonexistent", b, abi.env(CPPFLAGS=""))
    run("make -j8 libs", b, abi.env(CPPFLAGS=""))
    run(f"make install.libs install.includes DESTDIR={abi.sysroot}", b, abi.env(CPPFLAGS=""))
    # Many games include <curses.h>/<ncurses.h> directly.
    inc = os.path.join(abi.sysroot, "include")
    for h in os.listdir(os.path.join(inc, "ncursesw")):
        link = os.path.join(inc, h)
        if not os.path.exists(link):
            os.symlink(os.path.join("ncursesw", h), link)
    lib = os.path.join(abi.sysroot, "lib")
    for alias in ("libncurses.a", "libcurses.a"):
        if not os.path.exists(os.path.join(lib, alias)):
            os.symlink("libncursesw.a", os.path.join(lib, alias))


# ---------------------------------------------------------------- executables

def cc(abi, srcs, out, extra="", libs="", cxx=False, cwd=None):
    compiler = abi.cxx if cxx else abi.cc
    run(f"{compiler} {CFLAGS} -I{abi.sysroot}/include -I{abi.sysroot}/include/ncursesw "
        f"{extra} {' '.join(srcs)} -o {out} {LDFLAGS} {libs}", cwd or os.path.dirname(out))


def build_pty(abi):
    w = abi.work("pty")
    exe = os.path.join(w, "tuikio_pty")
    cc(abi, [os.path.join(HERE, "pty", "tuikio_pty.c")], exe, "-Wall -Wextra -Werror")
    return abi.install(exe, "tuikio_pty")


def build_2048(abi):
    w = abi.work("2048")
    fetch("2048-LICENSE")
    exe = os.path.join(w, "game2048")
    cc(abi, [fetch("2048.c")], exe, "-std=c99 -D_XOPEN_SOURCE=700")
    return abi.install(exe, "game_2048")


def build_vitetris(abi):
    w = abi.work("vitetris")
    src = unpack("vitetris_0.59.1.orig.tar.gz", os.path.join(w, "src"))
    apply_patches(src, "vitetris")
    run("./configure --without-x --without-network --without-joystick --without-curses "
        "--enable-2player --enable-term_resizing --enable-menu --enable-blockstyles "
        "--prefix=/ ", src, abi.env())
    run(f'make -j8 CC="{abi.cc}" CFLAGS="{CFLAGS} {LEGACY}" LDFLAGS="{LDFLAGS}"', src, abi.env())
    return abi.install(os.path.join(src, "tetris"), "game_vitetris")


def autotools(abi, src, args="", cflags=""):
    """Cross-configure an autotools tree; game data paths are always relative to $HOME
    because the app starts every game with its private save directory as cwd."""
    inc = f"-I{abi.sysroot}/include -I{abi.sysroot}/include/ncursesw"
    env = abi.env(CFLAGS=f"{CFLAGS} {LEGACY} {inc} {cflags}", LIBS=abi.curses_libs,
                  LDFLAGS=f"{LDFLAGS} -L{abi.sysroot}/lib")
    # Decades-old config.sub scripts predate musl (and sometimes x86_64); use current ones.
    for helper in ("config.sub", "config.guess"):
        if os.path.exists(os.path.join(src, helper)):
            shutil.copyfile(f"/usr/share/misc/{helper}", os.path.join(src, helper))
    run(f"./configure --host={abi.triple} --build=x86_64-pc-linux-gnu --prefix=/ {args}", src, env)
    run("make -j8", src, env)


def build_robotfindskitten(abi):
    w = abi.work("robotfindskitten")
    src = unpack("robotfindskitten_2.8284271.702.orig.tar.gz", os.path.join(w, "src"))
    # Messages ship as data (assets/games/robotfindskitten/), installed to ~/.robotfindskitten.
    autotools(abi, src, cflags='-DSYSTEM_NKI_DIR=\\".robotfindskitten\\"')
    data = os.path.join(OUT, "assets", "games", "robotfindskitten")
    os.makedirs(data, exist_ok=True)
    shutil.copyfile(os.path.join(src, "nki", "vanilla.nki"), os.path.join(data, "vanilla.nki"))
    return abi.install(os.path.join(src, "src", "robotfindskitten"), "game_robotfindskitten")


def build_moonbuggy(abi):
    w = abi.work("moon-buggy")
    src = unpack("moon-buggy_1.0.51.orig.tar.gz", os.path.join(w, "src"))
    autotools(abi, src, "--sharedstatedir=/nonexistent")
    # Rebuild highscore.o with the score file in the game's own directory.
    run(f'make highscore.o scoredir=. -B CFLAGS="{CFLAGS} {LEGACY}" && make', src,
        abi.env(LIBS=abi.curses_libs, LDFLAGS=f"{LDFLAGS} -L{abi.sysroot}/lib"))
    return abi.install(os.path.join(src, "moon-buggy"), "game_moonbuggy")


def build_freesweep(abi):
    w = abi.work("freesweep")
    src = unpack("freesweep_1.0.2.orig.tar.gz", os.path.join(w, "src"))
    autotools(abi, src, "--with-scoresdir=. --sysconfdir=/nonexistent")
    return abi.install(os.path.join(src, "freesweep"), "game_freesweep")


def build_ninvaders(abi):
    w = abi.work("ninvaders")
    src = unpack("ninvaders_0.1.1.orig.tar.gz", os.path.join(w, "src"))
    srcs = [os.path.join(src, f) for f in
            ("globals.c", "view.c", "aliens.c", "ufo.c", "player.c", "nInvaders.c")]
    exe = os.path.join(src, "nInvaders")
    cc(abi, srcs, exe, f"-I{src} {LEGACY} -fcommon", abi.curses_libs)
    return abi.install(exe, "game_ninvaders")


def build_greed(abi):
    w = abi.work("greed")
    src = unpack("greed_4.2.orig.tar.gz", os.path.join(w, "src"))
    exe = os.path.join(src, "greed")
    # Scores and their lock file live next to the game's saves, not in /var or /tmp.
    cc(abi, [os.path.join(src, "greed.c")], exe,
       f'{LEGACY} -DSCOREFILE=\\".greedscores\\" -DLOCKPATH=\\".greed.lock\\" -DRELEASE=\\"4.2\\"',
       abi.curses_libs)
    return abi.install(exe, "game_greed")


def build_nsnake(abi):
    w = abi.work("nsnake")
    src = unpack("nsnake_3.0.1.orig.tar.gz", os.path.join(w, "src"))
    srcs = []
    for root, _, files in os.walk(src):
        for f in files:
            if f.endswith(".cpp") and "/doc" not in root:
                srcs.append(os.path.join(root, f))
    objs = []
    for root, _, files in os.walk(os.path.join(src, "deps")):
        for f in files:
            if f.endswith(".c"):
                obj = os.path.join(root, f[:-2] + ".o")
                run(f"{abi.cc} {CFLAGS} -c {os.path.join(root, f)} -o {obj}", src)
                objs.append(obj)
    exe = os.path.join(src, "nsnake")
    cc(abi, sorted(srcs) + objs, exe,
       f'-I{src}/src -I{src}/deps -DVERSION=\\"3.0.1\\" -DDATE=\\"Mar2016\\" -DPACKAGE=\\"nsnake\\" -DSYSTEM_LEVEL_DIR=\\"levels\\" -std=c++11',
       abi.curses_libs, cxx=True)
    return abi.install(exe, "game_nsnake")


def build_lantern_tide(abi):
    w = abi.work("lantern-tide")
    exe = os.path.join(w, "lantern_tide")
    cc(abi, [os.path.join(HERE, "original", "lantern-tide", "lantern_tide.c")], exe, "-std=c11 -Wall -Wextra -Werror")
    return abi.install(exe, "game_lantern_tide")


GAMES = {
    "lantern-tide": build_lantern_tide,
    "2048": build_2048,
    "vitetris": build_vitetris,
    "robotfindskitten": build_robotfindskitten,
    "moon-buggy": build_moonbuggy,
    "freesweep": build_freesweep,
    "ninvaders": build_ninvaders,
    "greed": build_greed,
    "nsnake": build_nsnake,
}


# License texts that ship with the binaries, taken from the pinned sources themselves.
NOTICES = {
    "2048": ("2048-LICENSE", None),
    "vitetris": ("vitetris_0.59.1.orig.tar.gz", "licence.txt"),
    "robotfindskitten": ("robotfindskitten_2.8284271.702.orig.tar.gz", "COPYING"),
    "moon-buggy": ("moon-buggy_1.0.51.orig.tar.gz", "COPYING"),
    "freesweep": ("freesweep_1.0.2.orig.tar.gz", "COPYING"),
    "ninvaders": ("ninvaders_0.1.1.orig.tar.gz", "gpl.txt"),
    "greed": ("greed_4.2.orig.tar.gz", "COPYING"),
    "nsnake": ("nsnake_3.0.1.orig.tar.gz", "COPYING"),
    "ncurses": ("ncurses_6.4+20240113.orig.tar.gz", "COPYING"),
}


def tar_text(name, rel):
    """Reads <top>/<rel> from a verified source archive without unpacking it."""
    with tarfile.open(fetch(name)) as tf:
        for m in tf.getmembers():
            parts = m.name.split("/")
            if m.isfile() and len(parts) == 2 and parts[1] == rel:
                return tf.extractfile(m).read().decode("utf-8", "replace")
    sys.exit(f"[native] {rel} not found in {name}")


LICENSES = os.path.normpath(os.path.join(HERE, "..", "LICENSES"))


def write_notices(games):
    """LICENSES/ (in the repository, bundled into the APK as assets/licenses): each component's
    license text plus where its exact source lives (GPL sections 3/6: the corresponding source
    for every binary in the APK)."""
    dst = LICENSES
    os.makedirs(dst, exist_ok=True)
    for comp in [g for g in games if g in NOTICES] + ["ncurses"]:
        name, rel = NOTICES[comp]
        text = open(fetch(name), encoding="utf-8").read() if rel is None else tar_text(name, rel)
        with open(os.path.join(dst, f"{comp}.txt"), "w") as fh:
            fh.write(text)
    musl = os.path.join(os.path.dirname(os.path.realpath(zig())), "lib", "libc", "musl", "COPYRIGHT")
    shutil.copyfile(musl, os.path.join(dst, "musl.txt"))
    shutil.copyfile(os.path.join(HERE, "..", "LICENSE"), os.path.join(dst, "tuikio-GPL-3.0.txt"))
    lock = lockfile()
    lines = [
        "Sources of the native programs in this app",
        "",
        "Every game binary is built from the exact archives below (checked by SHA-256) with the",
        "scripts and patches in the native/ directory of https://github.com/omersusin/tuikio",
        "(build: python3 native/build.py). The Tuikio PTY helper and Lantern Tide are original",
        "Tuikio code in that repository (GPL-3.0-or-later). Release pages also attach these",
        "archives, so the corresponding source stays available even if a mirror disappears.",
        "",
    ]
    for fname in sorted(lock):
        lines += [fname, f"  {lock[fname]['url']}", f"  sha256 {lock[fname]['sha256']}", ""]
    with open(os.path.join(dst, "SOURCES.txt"), "w") as fh:
        fh.write("\n".join(lines))


def build_abi(abi_name, games):
    abi = Abi(abi_name)
    results = {"tuikio_pty": build_pty(abi)}
    needs_curses = [g for g in games if g not in ("2048", "vitetris", "lantern-tide")]
    if needs_curses:
        build_ncurses(abi)
    for g in games:
        log(f"{abi.name}: {g}")
        results[g] = GAMES[g](abi)
    return abi_name, results


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--abi", action="append", choices=sorted(ABIS), help="ABI(s) to build (default: all)")
    ap.add_argument("--game", action="append", choices=sorted(GAMES), help="game(s) to build (default: all)")
    ap.add_argument("--clean", action="store_true", help="remove native/build first")
    args = ap.parse_args()
    if args.clean and os.path.isdir(BUILD):
        shutil.rmtree(BUILD)
    abis = args.abi or list(ABIS)
    games = args.game or list(GAMES)
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(abis)) as pool:
        done = dict(pool.map(lambda a: build_abi(a, games), abis))
    write_notices(games)
    manifest_path = os.path.join(OUT, "manifest.json")
    manifest = {}
    if os.path.exists(manifest_path):
        with open(manifest_path) as fh:
            manifest = json.load(fh)
    for abi_name, outputs in done.items():
        entry = manifest.setdefault(abi_name, {})
        for _, path in sorted(outputs.items()):
            entry[os.path.basename(path)] = {"bytes": os.path.getsize(path), "sha256": sha256(path)}
    with open(manifest_path, "w") as fh:
        json.dump(manifest, fh, indent=2, sort_keys=True)
    for abi_name in sorted(done):
        for name, meta in sorted(manifest[abi_name].items()):
            log(f"{abi_name:12} {name:28} {meta['bytes']:>9,} B")


if __name__ == "__main__":
    main()
