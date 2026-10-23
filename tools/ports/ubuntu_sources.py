#!/usr/bin/env python3
"""Looks up source packages in Ubuntu 24.04 (noble) through the archive's signed index:
InRelease is verified with the Ubuntu archive key, the Sources indexes are checked against it,
and the file hashes printed here are what native/sources.lock.json pins.

    python3 tools/ports/ubuntu_sources.py bsdgames nudoku ...
    python3 tools/ports/ubuntu_sources.py --json bsdgames      # lock entries
    python3 tools/ports/ubuntu_sources.py --lock bsdgames      # add them to the lock file
"""
import hashlib
import json
import lzma
import os
import subprocess
import sys
import urllib.request

MIRROR = "http://archive.ubuntu.com/ubuntu"
CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "native", ".cache", "ubuntu-index")
KEYRING = "/usr/share/keyrings/ubuntu-archive-keyring.gpg"


def fetch(url, dst):
    if not os.path.exists(dst):
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        urllib.request.urlretrieve(url, dst + ".part")
        os.rename(dst + ".part", dst)
    return dst


def index():
    inrelease = fetch(f"{MIRROR}/dists/noble/InRelease", os.path.join(CACHE, "InRelease"))
    subprocess.run(["gpgv", "--keyring", KEYRING, inrelease], check=True, capture_output=True)
    text = open(inrelease, encoding="utf-8").read()
    pkgs = {}
    for comp in ("main", "universe", "multiverse"):
        path = fetch(f"{MIRROR}/dists/noble/{comp}/source/Sources.xz", os.path.join(CACHE, f"Sources-{comp}.xz"))
        digest = hashlib.sha256(open(path, "rb").read()).hexdigest()
        if f" {digest} " not in text or f"{comp}/source/Sources.xz" not in text:
            sys.exit(f"{comp} Sources.xz does not match the signed InRelease")
        for para in lzma.open(path, "rt", encoding="utf-8").read().split("\n\n"):
            fields, key = {}, None
            for line in para.splitlines():
                if line.startswith(" ") and key:
                    fields[key] += "\n" + line.strip()
                elif ":" in line:
                    key, _, val = line.partition(":")
                    fields[key] = val.strip()
            if "Package" in fields:
                pkgs[fields["Package"]] = dict(fields, Component=comp)
    return pkgs


def files(p):
    out = []
    for line in p.get("Checksums-Sha256", "").splitlines():
        parts = line.split()
        if len(parts) == 3:
            sha, size, name = parts
            out.append({"name": name, "sha256": sha, "size": int(size), "url": f"{MIRROR}/{p['Directory']}/{name}"})
    return out


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    pkgs = index()
    lock = {}
    for name in args:
        p = pkgs.get(name)
        if not p:
            print(f"{name:22} -- not in noble")
            continue
        fs = files(p)
        if "--json" in sys.argv or "--lock" in sys.argv:
            for f in fs:
                if not f["name"].endswith(".dsc"):
                    lock[f["name"]] = {"url": f["url"], "sha256": f["sha256"]}
        else:
            print(f"{name:22} {p['Version']:24} {p['Component']:9} {' '.join(f['name'] for f in fs if not f['name'].endswith('.dsc'))}")
    if lock and "--lock" in sys.argv:
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "native", "sources.lock.json")
        data = json.load(open(path))
        for k, v in lock.items():
            old = data["files"].get(k)
            if old and old["sha256"] != v["sha256"]:
                sys.exit(f"{k}: lock pins {old['sha256']}, archive has {v['sha256']}")
            data["files"][k] = v
        with open(path, "w") as fh:
            json.dump(data, fh, indent=2)
            fh.write("\n")
        print(f"locked {len(lock)} files")
    elif lock:
        print(json.dumps(lock, indent=2))


if __name__ == "__main__":
    main()
