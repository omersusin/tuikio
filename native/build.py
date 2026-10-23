#!/usr/bin/env python3
"""Build Tuikio's native executables for Android: the PTY helper and every game port.

Each output is a self-contained static-PIE ELF executable linked against musl libc with Zig's
cross toolchain, installed as lib<name>.so (see tk.py and docs/RUNTIME.md). Recipes live in
native/recipes/, one module per source family.

    python3 native/build.py                         # all ABIs, all games
    python3 native/build.py --abi x86_64 --game 2048
    python3 native/build.py --list
    ZIG=/path/to/zig python3 native/build.py

Outputs:  native/build/out/<abi>/lib<name>.so     (copied into the APK by the app build)
          native/build/out/assets/...             (game data files)
          native/build/out/manifest.json          (sizes + SHA-256 of every output)
          LICENSES/<game>.txt, LICENSES/SOURCES.txt

Sources are downloaded once into native/.cache/dl and verified against native/sources.lock.json
before anything is unpacked. A mismatch fails that game's build.
"""
import argparse
import concurrent.futures
import json
import os
import shutil
import sys
import tarfile
import traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import tk  # noqa: E402
import recipes  # noqa: E402,F401  (registers every recipe)
from tk import ABIS, BUILD, GAMES, HERE, LICENSES, NEEDS_CURSES, NOTICES, OUT, BuildError, log  # noqa: E402

NOTICES["ncurses"] = ("ncurses_6.4+20240113.orig.tar.gz", "COPYING")


def build_runtime(abi):
    """The PTY helper every game runs under, and the pager games get as $PAGER."""
    out = {}
    for name in ("tuikio_pty", "tuikio_pager"):
        exe = os.path.join(abi.work("pty"), name)
        tk.cc(abi, [os.path.join(HERE, "pty", f"{name}.c")], exe, "-Wall -Wextra -Werror")
        out[name] = abi.install(exe, name)
    return out


def build_one(abi, game):
    """Runs one recipe; returns ({lib name: installed path}, error text or None)."""
    try:
        built = GAMES[game](abi)
        return {name: abi.install(path, name) for name, path in built.items()}, None
    except BuildError as e:
        return {}, str(e)
    except Exception:  # a recipe bug must not hide the other games' results
        return {}, traceback.format_exc()


def build_abi(abi_name, games, jobs):
    abi = tk.Abi(abi_name)
    results, failures = build_runtime(abi), {}
    if any(g in NEEDS_CURSES for g in games):
        tk.build_ncurses(abi)
    with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:
        futures = {pool.submit(build_one, abi, g): g for g in games}
        for f in concurrent.futures.as_completed(futures):
            g = futures[f]
            outputs, error = f.result()
            if error:
                failures[g] = error
                log(f"{abi.name}: {g} FAILED")
            else:
                results.update(outputs)
                log(f"{abi.name}: {g} ok ({', '.join(sorted(outputs))})")
    return abi_name, results, failures


def tar_text(name, rel):
    """Reads <top>/<rel> from a verified source archive without unpacking it."""
    with tarfile.open(tk.fetch(name)) as tf:
        for m in tf.getmembers():
            parts = m.name.split("/", 1)
            if m.isfile() and len(parts) == 2 and parts[1] == rel:
                return tf.extractfile(m).read().decode("utf-8", "replace")
    raise BuildError(f"{rel} not found in {name}")


def write_notices(games):
    """LICENSES/ (in the repository, bundled into the APK as assets/licenses): each component's
    license text plus where its exact source lives (GPL sections 3/6: the corresponding source
    for every binary in the APK). Recipes may also install license files themselves."""
    os.makedirs(LICENSES, exist_ok=True)
    for comp in sorted({tk.GAME_LICENSE[g] for g in games if g in tk.GAME_LICENSE} | {"ncurses"}):
        name, rel = NOTICES[comp]
        if rel is None:
            text = open(tk.fetch(name), encoding="utf-8", errors="replace").read()
        elif isinstance(rel, (tuple, list)):  # several license files, e.g. a dual license
            text = "\n\n".join(tar_text(name, r) for r in rel)
        else:
            text = tar_text(name, rel)
        with open(os.path.join(LICENSES, f"{comp}.txt"), "w") as fh:
            fh.write(text)
    musl = os.path.join(os.path.dirname(os.path.realpath(tk.zig())), "lib", "libc", "musl", "COPYRIGHT")
    shutil.copyfile(musl, os.path.join(LICENSES, "musl.txt"))
    shutil.copyfile(os.path.join(HERE, "..", "LICENSE"), os.path.join(LICENSES, "tuikio-GPL-3.0.txt"))
    lock = tk.lockfile()
    lines = [
        "Sources of the native programs in this app",
        "",
        "Every game binary is built from the exact sources below (checked by SHA-256; git sources",
        "also by commit) with the scripts and patches in the native/ directory of",
        "https://github.com/omersusin/tuikio (build: python3 native/build.py). The Tuikio PTY helper",
        "and Lantern Tide are original Tuikio code in that repository (GPL-3.0-or-later). Release",
        "pages also attach these archives, so the corresponding source stays available even if a",
        "mirror disappears.",
        "",
    ]
    for fname in sorted(lock):
        e = lock[fname]
        where = f"  {e['git']} @ {e['commit']}" if "git" in e else f"  {e['url']}"
        lines += [fname, where, f"  sha256 {e['sha256']}", ""]
    with open(os.path.join(LICENSES, "SOURCES.txt"), "w") as fh:
        fh.write("\n".join(lines))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--abi", action="append", choices=sorted(ABIS), help="ABI(s) to build (default: all)")
    ap.add_argument("--game", action="append", choices=sorted(GAMES), metavar="GAME", help="game(s) to build (default: all)")
    ap.add_argument("--list", action="store_true", help="list the recipes and exit")
    ap.add_argument("--jobs", type=int, default=max(1, (os.cpu_count() or 2) // 2), help="games built in parallel per ABI")
    ap.add_argument("--keep-going", action="store_true", help="exit 0 even if some games failed")
    ap.add_argument("--clean", action="store_true", help="remove native/build first")
    args = ap.parse_args()
    if args.list:
        print("\n".join(sorted(GAMES)))
        return
    if args.clean and os.path.isdir(BUILD):
        shutil.rmtree(BUILD)
    abis = args.abi or list(ABIS)
    games = args.game or sorted(GAMES)
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(abis)) as pool:
        done = list(pool.map(lambda a: build_abi(a, games, args.jobs), abis))
    failed = {g for _, _, fails in done for g in fails}
    write_notices([g for g in games if g not in failed])
    manifest_path = os.path.join(OUT, "manifest.json")
    manifest = {}
    if os.path.exists(manifest_path):
        with open(manifest_path) as fh:
            manifest = json.load(fh)
    for abi_name, outputs, _ in done:
        entry = manifest.setdefault(abi_name, {})
        for _, path in sorted(outputs.items()):
            entry[os.path.basename(path)] = {"bytes": os.path.getsize(path), "sha256": tk.sha256(path)}
    for abi_name, entry in manifest.items():  # forget outputs that were removed since
        for name in [n for n in entry if not os.path.exists(os.path.join(OUT, abi_name, n))]:
            del entry[name]
    with open(manifest_path, "w") as fh:
        json.dump(manifest, fh, indent=2, sort_keys=True)
    for abi_name, outputs, _ in done:
        total = sum(os.path.getsize(p) for p in outputs.values())
        log(f"{abi_name:12} {len(outputs)} executables, {total:,} B")
    for abi_name, _, fails in done:
        for g, err in sorted(fails.items()):
            print(f"\n[native] {abi_name}: {g} FAILED\n{err}", file=sys.stderr)
    if failed:
        log(f"failed: {', '.join(sorted(failed))}")
        if not args.keep_going:
            sys.exit(1)


if __name__ == "__main__":
    main()
