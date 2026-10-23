"""Toolkit for native/build.py and the recipes in native/recipes/: verified sources, the Zig
cross toolchain per ABI, and small build helpers.

Every output is a self-contained static-PIE ELF executable linked against musl libc. Android
installs files named lib*.so from the APK into the app's read-only nativeLibraryDir, the only
place an app targeting API 29+ may execute from (docs/RUNTIME.md), so each executable is
installed as lib<name>.so.
"""
import hashlib
import json
import os
import shutil
import subprocess
import tarfile
import threading
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, ".."))
CACHE = os.path.join(HERE, ".cache")
DL = os.path.join(CACHE, "dl")
BUILD = os.path.join(HERE, "build")
OUT = os.path.join(BUILD, "out")
ASSETS = os.path.join(OUT, "assets")
LICENSES = os.path.join(ROOT, "LICENSES")

ABIS = {
    "arm64-v8a": "aarch64-linux-musl",
    "armeabi-v7a": "arm-linux-musleabihf",
    "x86_64": "x86_64-linux-musl",
}

# Hardened, size-optimised static-PIE. 16 KB segment alignment keeps the binaries loadable on
# Android 15+ devices that use 16 KB memory pages.
CFLAGS = "-Os -fPIE -ffunction-sections -fdata-sections -fstack-protector-strong -Wno-error"
# Pre-C99 code in older games: keep it compiling with modern clang. Implicit declarations stay
# a hard error, though: in old C they silently truncate returned pointers to 32 bits on 64-bit
# targets, which corrupts memory at run time.
LEGACY = ("-Wno-implicit-int -Wno-int-conversion -Wno-incompatible-pointer-types -Wno-return-type "
          "-Wno-deprecated-non-prototype -Werror=implicit-function-declaration -fcommon")
LDFLAGS = "-static -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,-z,relro,-z,now -s"
COMPAT_WRAPS = ("setuid", "setgid", "seteuid", "setegid", "setreuid", "setregid", "setresuid",
                "setresgid", "setgroups", "getpwuid", "getpwnam")


class BuildError(Exception):
    pass


_log_lock = threading.Lock()


def log(msg):
    with _log_lock:
        print(f"[native] {msg}", flush=True)


def run(cmd, cwd, env=None, logname="build.log"):
    """Runs a shell command, logging to <cwd>/<logname>; raises BuildError with the log tail."""
    os.makedirs(cwd, exist_ok=True)
    path = os.path.join(cwd, logname)
    with open(path, "ab") as fh:
        fh.write(f"\n$ {cmd}\n".encode())
        fh.flush()
        proc = subprocess.run(cmd, shell=True, cwd=cwd, env=env, stdout=fh, stderr=subprocess.STDOUT)
    if proc.returncode != 0:
        with open(path, "rb") as fh:
            tail = fh.read()[-3000:].decode(errors="replace")
        raise BuildError(f"failed in {cwd}: {cmd}\n{tail}")


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


_fetch_lock = threading.Lock()


def fetch(name):
    """Downloads a pinned source once and verifies it.

    Lock entries are either {"url", "sha256"} for files, or {"git", "commit"} for repositories:
    the exact commit is fetched and checked (git object ids are content hashes), then archived
    to a tarball whose SHA-256 is pinned too, so a rebuilt archive must match byte for byte.
    """
    entry = lockfile()[name]
    os.makedirs(DL, exist_ok=True)
    dst = os.path.join(DL, name)
    with _fetch_lock:
        if not os.path.exists(dst):
            if "git" in entry:
                _fetch_git(entry, dst)
            else:
                log(f"download {entry['url']}")
                tmp = dst + ".part"
                req = urllib.request.Request(entry["url"], headers={"User-Agent": "tuikio-build"})
                with urllib.request.urlopen(req, timeout=180) as r, open(tmp, "wb") as fh:
                    shutil.copyfileobj(r, fh)
                os.replace(tmp, dst)
    got = sha256(dst)
    if got != entry["sha256"]:
        os.remove(dst)
        raise BuildError(f"checksum mismatch for {name}: expected {entry['sha256']}, got {got}")
    return dst


def _fetch_git(entry, dst):
    url, commit = entry["git"], entry["commit"]
    repo = os.path.join(CACHE, "git", hashlib.sha256(url.encode()).hexdigest()[:16])
    log(f"git {url} @ {commit[:12]}")
    if not os.path.isdir(os.path.join(repo, ".git")):
        os.makedirs(repo, exist_ok=True)
        subprocess.run(["git", "init", "-q", repo], check=True)
    subprocess.run(["git", "-C", repo, "fetch", "-q", "--depth", "1", url, commit], check=True, timeout=600)
    head = subprocess.run(["git", "-C", repo, "rev-parse", "FETCH_HEAD"], check=True, capture_output=True, text=True).stdout.strip()
    if head != commit:
        raise BuildError(f"{url}: fetched {head}, expected {commit}")
    # Deterministic tarball of exactly that tree (no submodules, no host-specific mtimes).
    tmp = dst + ".part"
    with open(tmp, "wb") as fh:
        subprocess.run(["git", "-C", repo, "-c", "tar.umask=0022", "archive", "--format=tar",
                        "--prefix=src/", "--mtime=1970-01-01 00:00:00 +0000", commit], check=True, stdout=fh)
    os.replace(tmp, dst)


def unpack(name, dest):
    """Fresh, verified copy of a source archive; returns the top-level directory."""
    archive = fetch(name)
    if os.path.exists(dest):
        shutil.rmtree(dest)
    os.makedirs(dest)
    real = os.path.realpath(dest)
    with tarfile.open(archive) as tf:
        for m in tf.getmembers():
            target = os.path.realpath(os.path.join(dest, m.name))
            if target != real and not target.startswith(real + os.sep):
                raise BuildError(f"refusing path outside archive root: {m.name}")
            if m.issym() or m.islnk():
                link = os.path.realpath(os.path.join(os.path.dirname(target), m.linkname))
                if not link.startswith(real + os.sep):
                    raise BuildError(f"refusing link outside archive root: {m.name}")
        tf.extractall(dest, filter="data")
    tops = os.listdir(dest)
    return os.path.join(dest, tops[0]) if len(tops) == 1 else dest


def apply_patches(src, game):
    """Applies native/ports/<game>/patches/*.patch in name order."""
    pdir = os.path.join(HERE, "ports", game, "patches")
    if not os.path.isdir(pdir):
        return
    for p in sorted(os.listdir(pdir)):
        if p.endswith(".patch"):
            run(f"patch -p1 --forward --batch < {os.path.join(pdir, p)}", src)


def apply_debian_patches(src, debian_tarball, skip=()):
    """Applies a Debian source package's patch series (the distribution's maintenance fixes)."""
    deb = os.path.join(os.path.dirname(src), "debian-pkg")
    unpack(debian_tarball, deb)
    series = os.path.join(deb, "patches", "series")
    if not os.path.exists(series):
        series = os.path.join(deb, "debian", "patches", "series")
    pdir = os.path.dirname(series)
    if not os.path.exists(series):
        return
    for line in open(series):
        p = line.split("#")[0].strip()
        if not p or p.split()[0] in skip:
            continue
        run(f"patch -p1 --forward --batch < {os.path.join(pdir, p.split()[0])}", src)


def copy_license(name, path):
    """Installs a game's license text as LICENSES/<name>.txt (bundled in the APK)."""
    os.makedirs(LICENSES, exist_ok=True)
    shutil.copyfile(path, os.path.join(LICENSES, f"{name}.txt"))


def install_asset(rel, path):
    """Adds a data file to the APK's assets (installed into a game's home by the app)."""
    dst = os.path.join(ASSETS, rel)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copyfile(path, dst)
    return dst


# ---------------------------------------------------------------- toolchain

def zig():
    z = os.environ.get("ZIG") or shutil.which("zig") or "/opt/tuikio-tools/zig/ziglang/zig"
    if not os.path.exists(z) and not shutil.which(z):
        raise BuildError("zig not found; set ZIG (see docs/BUILD.md)")
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
        self.lock = threading.Lock()
        # Every game links the Android compatibility shim (native/compat): an object rather
        # than an archive, so it is linked wherever LDFLAGS lands on the command line.
        compat = os.path.join(self.root, "compat", "tuikio_compat.o")
        run(f"{self.cc} {CFLAGS} -Wall -Wextra -Werror -c {os.path.join(HERE, 'compat', 'tuikio_compat.c')} -o {compat}",
            os.path.dirname(compat))
        wraps = ",".join(f"--wrap={s}" for s in COMPAT_WRAPS)
        self.ldflags = f"{LDFLAGS} {compat} -Wl,{wraps}"

    def work(self, game):
        d = os.path.join(self.root, "work", game)
        os.makedirs(d, exist_ok=True)
        return d

    def env(self, **extra):
        e = dict(os.environ)
        e.update(CC=self.cc, CXX=self.cxx, AR=self.ar, RANLIB=self.ranlib,
                 CFLAGS=CFLAGS, CXXFLAGS=CFLAGS, LDFLAGS=self.ldflags,
                 CPPFLAGS=f"-I{self.sysroot}/include -I{self.sysroot}/include/ncursesw")
        e.update(extra)
        return e

    @property
    def curses_libs(self):
        return f"-L{self.sysroot}/lib -lncursesw"

    @property
    def curses_cflags(self):
        return f"-I{self.sysroot}/include -I{self.sysroot}/include/ncursesw -D_XOPEN_SOURCE_EXTENDED -D_DEFAULT_SOURCE"

    def install(self, built, name):
        dst = os.path.join(self.out, f"lib{name}.so")
        shutil.copyfile(built, dst)
        os.chmod(dst, 0o755)
        return dst


def cc(abi, srcs, out, extra="", libs="", cxx=False, cwd=None):
    compiler = abi.cxx if cxx else abi.cc
    run(f"{compiler} {CFLAGS} -I{abi.sysroot}/include -I{abi.sysroot}/include/ncursesw "
        f"{extra} {' '.join(srcs)} -o {out} {abi.ldflags} {libs}", cwd or os.path.dirname(out))


def compile_objects(abi, srcs, objdir, extra="", cxx=False, cwd=None):
    """Compiles each source to objdir; returns the object paths."""
    os.makedirs(objdir, exist_ok=True)
    objs = []
    for s in srcs:
        o = os.path.join(objdir, os.path.relpath(s, cwd or os.path.dirname(s)).replace("/", "__").rsplit(".", 1)[0] + ".o")
        compiler = abi.cxx if (cxx or s.endswith((".cc", ".cpp", ".cxx"))) else abi.cc
        run(f"{compiler} {CFLAGS} {abi.curses_cflags} {extra} -c {s} -o {o}", cwd or objdir)
        objs.append(o)
    return objs


def link(abi, objs, out, libs="", cxx=False, cwd=None):
    compiler = abi.cxx if cxx else abi.cc
    run(f"{compiler} {' '.join(objs)} -o {out} {abi.ldflags} {libs}", cwd or os.path.dirname(out))


def autotools(abi, src, args="", cflags="", make="make -j8", env_extra=None):
    """Cross-configures and builds an autotools tree; game data paths should be relative to
    $HOME because the app starts every game with its private home as working directory."""
    inc = f"-I{abi.sysroot}/include -I{abi.sysroot}/include/ncursesw"
    env = abi.env(CFLAGS=f"{CFLAGS} {LEGACY} {inc} {cflags}", CXXFLAGS=f"{CFLAGS} {inc} {cflags}",
                  LIBS=abi.curses_libs, LDFLAGS=f"{abi.ldflags} -L{abi.sysroot}/lib")
    if env_extra:
        env.update(env_extra)
    # Decades-old config.sub scripts predate musl (and sometimes x86_64); use current ones.
    for dirpath, _, files in os.walk(src):
        for helper in ("config.sub", "config.guess"):
            if helper in files:
                shutil.copyfile(f"/usr/share/misc/{helper}", os.path.join(dirpath, helper))
    run(f"./configure --host={abi.triple} --build=x86_64-pc-linux-gnu --prefix=/ {args}", src, env)
    run(make, src, env)
    return env


def build_ncurses(abi):
    stamp = os.path.join(abi.sysroot, "lib", "libncursesw.a")
    with abi.lock:
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
        # Many games include <curses.h>/<ncurses.h> directly and link -lncurses/-lcurses.
        inc = os.path.join(abi.sysroot, "include")
        for h in os.listdir(os.path.join(inc, "ncursesw")):
            dst = os.path.join(inc, h)
            if not os.path.exists(dst):
                os.symlink(os.path.join("ncursesw", h), dst)
        lib = os.path.join(abi.sysroot, "lib")
        for alias in ("libncurses.a", "libcurses.a", "libtinfo.a", "libtermcap.a", "libncursesw.a"):
            if not os.path.exists(os.path.join(lib, alias)):
                os.symlink("libncursesw.a", os.path.join(lib, alias))


# ---------------------------------------------------------------- registry

GAMES = {}
"""id -> builder(abi) returning {executable_name: built_path} (or a single path for id)."""
NOTICES = {}
"""license file name (LICENSES/<name>.txt) -> (lock file name, path inside archive or None for a
plain file)."""
GAME_LICENSE = {}
"""game id -> license file name; games from one source tree share one text."""
NEEDS_CURSES = set()


def game(name, curses=True, notice=None, license_name=None):
    """Registers a recipe. [notice] = (lock name, path in archive) of its license text, written to
    LICENSES/<license_name or name>.txt."""
    def deco(fn):
        GAMES[name] = fn
        if curses:
            NEEDS_CURSES.add(name)
        if notice:
            NOTICES[license_name or name] = notice
            GAME_LICENSE[name] = license_name or name
        return fn
    return deco
