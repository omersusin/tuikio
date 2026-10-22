#!/usr/bin/env python3
"""Measures what each game costs while it runs: terminal output (what the app must parse and
draw), how often it wakes the app up, and CPU time of the helper + game. Uses the real
binaries and the same PTY helper and frame protocol as the app.

    python3 tools/perf/measure_games.py [native/build/out/x86_64] [--seconds 5] [--capture DIR]

Each game is measured twice: idle (no input, e.g. a title screen or a paused board) and
active (a key press every 150 ms, like steady play). Results are host (x86_64) numbers; they
show relative cost and regressions, not absolute phone battery figures.
"""
import argparse
import json
import os
import select
import struct
import subprocess
import tempfile
import time

GAMES = {
    # name: (args, keys cycled during the active run)
    "game_lantern_tide": ([], [b"\x1b[C", b"\x1b[A", b"\x1b[D", b"\x1b[B"]),
    "game_2048": ([], [b"\x1b[A", b"\x1b[C", b"\x1b[B", b"\x1b[D"]),
    "game_vitetris": ([], [b"\r", b"\x1b[D", b"\x1b[C", b"\x1b[A"]),
    "game_robotfindskitten": ([], [b" ", b"l", b"j", b"h", b"k"]),
    "game_moonbuggy": ([], [b" ", b" ", b"j", b" "]),
    "game_freesweep": (["--fast", "--alt-charset"], [b"l", b"j", b"h", b"k"]),
    "game_ninvaders": ([], [b" ", b"\x1b[D", b"\x1b[C"]),
    "game_greed": ([], [b"l", b"j", b"h", b"k"]),
    "game_nsnake": ([], [b"\r", b"\x1b[A", b"\x1b[C", b"\x1b[B"]),
}


def frame(kind, payload):
    return struct.pack(">BH", kind, len(payload)) + payload


def run(out_dir, exe, args, keys, seconds, active, capture):
    home = tempfile.mkdtemp(prefix="tuikio-perf-")
    nki = os.path.join(out_dir, "..", "assets", "games", "robotfindskitten", "vanilla.nki")
    if exe == "game_robotfindskitten" and os.path.exists(nki):
        os.makedirs(os.path.join(home, ".robotfindskitten"))
        with open(nki, "rb") as src, open(os.path.join(home, ".robotfindskitten", "vanilla.nki"), "wb") as dst:
            dst.write(src.read())
    env = {"HOME": home, "TERM": "xterm-256color", "COLORTERM": "truecolor", "LANG": "C.UTF-8", "PATH": "/nonexistent", "ESCDELAY": "40"}
    helper = os.path.join(out_dir, "libtuikio_pty.so")
    p = subprocess.Popen([helper, "80", "24", os.path.join(out_dir, f"lib{exe}.so")] + args,
                         stdin=subprocess.PIPE, stdout=subprocess.PIPE, env=env, cwd=home)
    # Let the game start and settle; startup output is not part of the steady-state numbers.
    warm_end = time.time() + 1.0
    while time.time() < warm_end:
        r, _, _ = select.select([p.stdout], [], [], 0.05)
        if r:
            os.read(p.stdout.fileno(), 65536)
    total, wakeups, k = 0, 0, 0
    captured = bytearray()
    start = time.time()
    next_key = start
    while time.time() - start < seconds:
        if active and time.time() >= next_key:
            p.stdin.write(frame(1, keys[k % len(keys)]))
            p.stdin.flush()
            k += 1
            next_key += 0.15
        r, _, _ = select.select([p.stdout], [], [], 0.01)
        if r:
            chunk = os.read(p.stdout.fileno(), 65536)
            if not chunk:
                break
            total += len(chunk)
            wakeups += 1
            if capture:
                captured += chunk
    elapsed = time.time() - start
    p.stdin.close()
    _, _, ru = os.wait4(p.pid, 0)
    if capture and active:
        with open(os.path.join(capture, f"{exe}.bin"), "wb") as fh:
            fh.write(captured)
    return {
        "bytes_per_s": round(total / elapsed),
        "wakeups_per_s": round(wakeups / elapsed, 1),
        # wait4 reports the helper plus its reaped child (the game), warm-up included.
        "cpu_ms_total": round((ru.ru_utime + ru.ru_stime) * 1000),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out", nargs="?", default="native/build/out/x86_64")
    ap.add_argument("--seconds", type=float, default=5.0)
    ap.add_argument("--capture", help="directory to save the active-run output streams")
    a = ap.parse_args()
    a.out = os.path.abspath(a.out)
    if a.capture:
        os.makedirs(a.capture, exist_ok=True)
    results = {}
    print(f"{'game':24} {'idle B/s':>9} {'idle wk/s':>9} {'idle cpu':>9} {'play B/s':>9} {'play wk/s':>9} {'play cpu':>9}")
    for exe, (args, keys) in GAMES.items():
        idle = run(a.out, exe, args, keys, a.seconds, False, None)
        play = run(a.out, exe, args, keys, a.seconds, True, a.capture)
        results[exe] = {"idle": idle, "active": play}
        print(f"{exe[5:]:24} {idle['bytes_per_s']:>9} {idle['wakeups_per_s']:>9} {idle['cpu_ms_total']:>7}ms "
              f"{play['bytes_per_s']:>9} {play['wakeups_per_s']:>9} {play['cpu_ms_total']:>7}ms")
    print(json.dumps({"seconds": a.seconds, "results": results}, indent=2))


if __name__ == "__main__":
    main()
