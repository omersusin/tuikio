#!/usr/bin/env python3
"""Pins a git source in native/sources.lock.json: fetches exactly <commit>, archives it the way
native/tk.py does, and records the archive's SHA-256 next to the commit.

    python3 tools/ports/git_lock.py <lock name>.tar <repository URL> <commit>
"""
import json
import os
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "native"))
import tk  # noqa: E402


def main():
    name, url, commit = sys.argv[1:4]
    if len(commit) != 40:
        sys.exit("give the full 40-character commit id")
    dst = os.path.join(tk.DL, name)
    os.makedirs(tk.DL, exist_ok=True)
    if os.path.exists(dst):
        os.remove(dst)
    tk._fetch_git({"git": url, "commit": commit}, dst)
    path = os.path.join(ROOT, "native", "sources.lock.json")
    data = json.load(open(path))
    data["files"][name] = {"git": url, "commit": commit, "sha256": tk.sha256(dst)}
    with open(path, "w") as fh:
        json.dump(data, fh, indent=2)
        fh.write("\n")
    print(f"{name}: {commit} sha256 {data['files'][name]['sha256']}")


if __name__ == "__main__":
    main()
