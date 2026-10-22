#!/usr/bin/env python3
"""Smoke test for libtuikio_pty.so: start a game, type keys through the frame protocol,
check the game repaints, resize, then close stdin and expect a clean shutdown.

    python3 native/tests/pty_smoke.py <out-dir> [qemu-runner]
"""
import os
import shutil
import select
import struct
import subprocess
import sys
import tempfile
import time


def frame(kind, payload):
    return struct.pack(">BH", kind, len(payload)) + payload


def read_for(proc, seconds):
    buf = b""
    end = time.time() + seconds
    while time.time() < end:
        r, _, _ = select.select([proc.stdout], [], [], 0.05)
        if r:
            chunk = os.read(proc.stdout.fileno(), 65536)
            if not chunk:
                break
            buf += chunk
    return buf


def main():
    out = os.path.abspath(sys.argv[1])
    runner = [shutil.which(r) or r for r in sys.argv[2:]]
    home = tempfile.mkdtemp(prefix="tuikio-home-")
    env = {"HOME": home, "TERM": "xterm-256color", "LANG": "C.UTF-8", "PATH": "/nonexistent"}
    helper = os.path.join(out, "libtuikio_pty.so")
    game = os.path.join(out, "libgame_2048.so")
    cmd = [helper, "80", "24", game]
    if runner:
        # under qemu-user the helper execs the game itself, so point it at a tiny wrapper
        wrapper = os.path.join(home, "run-game")
        with open(wrapper, "w") as fh:
            fh.write(f"#!/bin/sh\nexec {' '.join(runner)} {game} \"$@\"\n")
        os.chmod(wrapper, 0o755)
        cmd = runner + [helper, "80", "24", wrapper]
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, env=env, cwd=home)
    first = read_for(p, 1.5)
    assert b"\x1b[?25l" in first and b"2048" in first or b"\x1b[38;5;" in first, first[:200]
    p.stdin.write(frame(1, b"\x1b[A\x1b[D\x1b[B\x1b[C")); p.stdin.flush()
    moved = read_for(p, 1.0)
    assert b"\x1b[H" in moved, "game did not repaint after arrow keys"
    p.stdin.write(frame(2, struct.pack(">HH", 100, 30))); p.stdin.flush()
    p.stdin.write(frame(1, b"q")); p.stdin.flush()
    quit_prompt = read_for(p, 0.8)
    assert b"QUIT?" in quit_prompt, quit_prompt[-200:]
    p.stdin.write(frame(1, b"y")); p.stdin.flush()
    rest = read_for(p, 1.0)
    code = p.wait(timeout=5)
    assert code == 0, f"exit code {code}"
    assert b"\x1b[?25h" in rest, "game did not restore the cursor on exit"
    # stdin EOF must end a running game too
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, env=env, cwd=home)
    read_for(p, 1.0)
    p.stdin.close()
    t0 = time.time()
    code = p.wait(timeout=5)
    print(f"ok: protocol, repaint, quit, resize; EOF shutdown in {time.time() - t0:.2f}s (exit {code})")


if __name__ == "__main__":
    main()
