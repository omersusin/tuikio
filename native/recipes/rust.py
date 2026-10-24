"""Terminal games written in Rust, pinned by commit (tools/ports/git_lock.py) and built with cargo
for each ABI's musl target (tk.cargo). Their crates are pinned by Cargo.lock; for the two trees
that don't ship one, the lock lives in native/ports/<game>/Cargo.lock."""
import glob
import hashlib
import html
import json
import os
import re
import shutil
import subprocess
import threading

import tk
from tk import HERE, cargo, game, install_asset, unpack


def entry(lock, exe, binary, *, notice="LICENSE", args="", subdir="", data=()):
    return dict(lock=lock, exe=exe, binary=binary, notice=notice, args=args, subdir=subdir, data=data)


GAMES = {
    "ttyper": entry("ttyper-e218bad9.tar", "game_ttyper", "ttyper", notice="LICENSE.md"),
    "sudoku-rs": entry("sudoku-rs-c8b6572a.tar", "game_sudoku_rs", "sudoku-rs"),
    "minesweep-rs": entry("minesweep-rs-6ca1c255.tar", "game_minesweep_rs", "minesweep"),
    "tetro-tui": entry("tetro-tui-4b6c7cb3.tar", "game_tetro_tui", "tetro-tui"),
    "rhex": entry("rhex-dd1b6b66.tar", "game_rhex", "rhex"),
    # Its default settings; the manifest passes -c config.toml.
    "minesweeper-4d": entry("minesweeper-4d-rs-c9480eec.tar", "game_minesweeper_4d", "minesweeper_4d",
                            data=["config.toml"]),
    "balatro-tui": entry("balatrotui-01bbb9ac.tar", "game_balatro_tui", "balatro_tui", args="-p balatro_tui"),
    "inertia": entry("inertia-80886289.tar", "game_inertia", "inertia", notice=("LICENSE-MIT", "LICENSE-APACHE")),
    "blackguard": entry("blackguard-294bfd37.tar", "game_blackguard", "blackguard"),
}


def build(abi, gid):
    e = GAMES[gid]
    src = unpack(e["lock"], os.path.join(abi.work(gid), "src"))
    lock = os.path.join(HERE, "ports", gid, "Cargo.lock")
    if os.path.exists(lock):
        shutil.copyfile(lock, os.path.join(src, "Cargo.lock"))
    for rel in e["data"]:
        install_asset(f"games/{gid}/{rel}", os.path.join(src, rel))
    exe = cargo(abi, os.path.join(src, e["subdir"]), e["binary"], e["args"])
    with _notice_lock:
        if gid not in tk.EXTRA_NOTICES:
            tk.EXTRA_NOTICES[gid] = crate_notices(abi, os.path.join(src, e["subdir"]))
    return {e["exe"]: exe}


_notice_lock = threading.Lock()
LICENSE_FILE = re.compile(r"^(LICEN[CS]E|COPYING|COPYRIGHT|NOTICE|UNLICENSE)", re.I)


def crate_notices(abi, src):
    """The license texts of every crate linked into the program, and of Rust's standard library:
    MIT and Apache-2.0 ask for them to travel with binaries. Identical texts are printed once."""
    meta = json.loads(subprocess.run(
        # Not --offline: cargo build fetched only this target's crates, and metadata wants the
        # manifests of every platform's (the lock still pins them all).
        ["cargo", "metadata", "--format-version", "1", "--locked",
         "--filter-platform", tk.RUST_TARGETS[abi.name]],
        cwd=src, env=dict(os.environ, CARGO_HOME=tk.CARGO_HOME), check=True, capture_output=True, text=True).stdout)
    own = set(meta["workspace_members"])
    used = {n["id"] for n in meta["resolve"]["nodes"]}
    seen = {}
    out = ["", "", "=" * 72, "Rust crates linked into this game", "=" * 72]
    for p in sorted((p for p in meta["packages"] if p["id"] in used and p["id"] not in own),
                    key=lambda p: (p["name"], p["version"])):
        out += ["", f"{p['name']} {p['version']} ({p.get('license') or 'see below'})"]
        root = os.path.dirname(p["manifest_path"])
        for f in sorted(os.listdir(root)):
            if not LICENSE_FILE.match(f) or not os.path.isfile(os.path.join(root, f)):
                continue
            text = open(os.path.join(root, f), encoding="utf-8", errors="replace").read().strip()
            key = hashlib.sha256(text.encode()).hexdigest()[:12]
            if key in seen:
                out.append(f"  {f}: same text as {seen[key]}")
            else:
                seen[key] = f"{p['name']} {f}"
                out += [f"--- {f}", text]
    write_rust_std_notice()
    out += ["", "The Rust standard library linked into it is listed in rust-std.txt."]
    return "\n".join(out) + "\n"


def write_rust_std_notice():
    """LICENSES/rust-std.txt: the copyright notices of Rust's standard library (every Rust game
    links it), from the toolchain's own documentation."""
    doc = os.path.join(subprocess.run(["rustc", "--print", "sysroot"], check=True, capture_output=True, text=True)
                       .stdout.strip(), "share", "doc", "rust")
    page = html.unescape(re.sub(r"<[^>]+>", "", open(os.path.join(doc, "COPYRIGHT-library.html"), encoding="utf-8").read()))
    lines = [ln.strip() for ln in page.splitlines()]
    text = re.sub(r"\n{3,}", "\n\n", "\n".join(lines)).strip()
    texts = ["Rust standard library", text]
    for f in sorted(glob.glob(os.path.join(doc, "licenses", "*"))):
        texts.append(f"--- {os.path.basename(f)}\n" + open(f, encoding="utf-8", errors="replace").read().strip())
    path = os.path.join(tk.LICENSES, "rust-std.txt")
    body = "\n\n".join(texts) + "\n"
    if not os.path.exists(path) or open(path, encoding="utf-8").read() != body:
        with open(path + ".tmp", "w", encoding="utf-8") as fh:
            fh.write(body)
        os.replace(path + ".tmp", path)


for _g, _e in GAMES.items():
    game(_g, curses=False, notice=(_e["lock"], _e["notice"]))(lambda abi, _g=_g: build(abi, _g))
