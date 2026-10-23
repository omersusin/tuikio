#!/usr/bin/env python3
"""Static release checks for a built APK (no device needed):

  - every activity/alias target/application class in the manifest exists in classes.dex
    (catches shrinker keep-rule mistakes that would crash on launch);
  - the app is not debuggable; it targets the expected SDKs;
  - every ABI ships the same executables, each matching assets/native/manifest.json, and each game
    binary belongs to a game in the bundled catalog;
  - resources.arsc is stored uncompressed and 4-byte aligned;
  - no private keys, GitHub tokens or client secrets are embedded;
  - license texts and the source listing are present.

    python3 tools/release/check_apk.py app/build/outputs/apk/release/tuikio-release.apk
"""
import hashlib
import json
import re
import struct
import subprocess
import sys
import zipfile

SECRET_PATTERNS = [
    rb"-----BEGIN (RSA |EC |ENCRYPTED )?PRIVATE KEY-----",
    rb"\bghp_[A-Za-z0-9]{36}\b", rb"\bgithub_pat_[A-Za-z0-9_]{40,}\b", rb"\bghs_[A-Za-z0-9]{36}\b",
    rb"client_secret\"?\s*[:=]\s*\"[^\"]{8,}",
]


def dex_classes(data):
    """Class descriptors from a dex file's type_ids table."""
    string_ids_size, string_ids_off, type_ids_size, type_ids_off = struct.unpack_from("<IIII", data, 0x38)

    def string_at(idx):
        off = struct.unpack_from("<I", data, string_ids_off + 4 * idx)[0]
        while data[off] & 0x80:  # skip ULEB128 utf16 length
            off += 1
        off += 1
        end = data.index(b"\0", off)
        return data[off:end].decode("utf-8", "replace")

    return {string_at(struct.unpack_from("<I", data, type_ids_off + 4 * i)[0]) for i in range(type_ids_size)}


def main():
    apk = sys.argv[1]
    failures = []

    def check(ok, msg):
        print(("ok    " if ok else "FAIL  ") + msg)
        if not ok:
            failures.append(msg)

    z = zipfile.ZipFile(apk)
    names = z.namelist()
    dex = {n: z.read(n) for n in names if re.fullmatch(r"classes\d*\.dex", n)}
    classes = set().union(*(dex_classes(d) for d in dex.values()))
    tree = subprocess.run(["aapt2", "dump", "xmltree", "--file", "AndroidManifest.xml", apk], capture_output=True, text=True, check=True).stdout
    badging = subprocess.run(["aapt2", "dump", "badging", apk], capture_output=True, text=True, check=True).stdout
    pkg = re.search(r"package: name='([^']+)'", badging).group(1)

    comps = re.findall(r'E: (application|activity|activity-alias|service|receiver|provider) .*?\n((?:\s+A: .*\n)+)', tree)
    for kind, attrs in comps:
        attr = "targetActivity" if kind == "activity-alias" else "name"
        m = re.search(r':%s\([^)]*\)="([^"]+)"' % attr, attrs)
        if not m:
            continue
        cls = m.group(1)
        cls = pkg + cls if cls.startswith(".") else cls
        desc = "L" + cls.replace(".", "/") + ";"
        check(desc in classes, f"{kind} {cls} present in dex")

    check("debuggable" not in tree, "not debuggable")
    check("sdkVersion:'26'" in badging and "targetSdkVersion:'36'" in badging, "minSdk 26, targetSdk 36")

    info = {i.filename: i for i in z.infolist()}
    arsc = info.get("resources.arsc")
    if arsc:
        with open(apk, "rb") as fh:
            fh.seek(arsc.header_offset + 26)
            n, m = struct.unpack("<HH", fh.read(4))
        data_off = arsc.header_offset + 30 + n + m
        check(arsc.compress_type == zipfile.ZIP_STORED and data_off % 4 == 0, "resources.arsc stored and 4-byte aligned")

    manifest = json.loads(z.read("assets/native/manifest.json"))
    abis = sorted({n.split("/")[1] for n in names if n.startswith("lib/")})
    sets = {a: sorted(n.split("/")[2] for n in names if n.startswith(f"lib/{a}/")) for a in abis}
    check(len({tuple(v) for v in sets.values()}) == 1, f"same executables for {', '.join(abis)} ({len(sets[abis[0]])} each)")
    for a in abis:
        for so in sets[a]:
            want = manifest.get(a, {}).get(so, {}).get("sha256")
            got = hashlib.sha256(z.read(f"lib/{a}/{so}")).hexdigest()
            if want != got:
                check(False, f"lib/{a}/{so} matches native manifest")
    check(not failures or all("native manifest" not in f for f in failures), "all native binaries match assets/native/manifest.json")

    # Every game binary belongs to a game in the bundled catalog: nothing ships that the app can't
    # launch (or that wasn't reviewed with its game).
    catalog = json.loads(z.read("assets/catalog/catalog.json"))
    games = catalog.get("games", catalog) if isinstance(catalog, dict) else catalog
    exes = {f"lib{g['runtime']['executable']}.so" for g in games}
    stray = sorted(so for so in sets[abis[0]] if so.startswith("libgame_") and so not in exes)
    check(not stray, f"every game binary belongs to a catalog game{': stray ' + ', '.join(stray) if stray else ''}")

    leaks = []
    for n in names:
        blob = z.read(n)
        for pat in SECRET_PATTERNS:
            if re.search(pat, blob):
                leaks.append(f"{n}: {pat.decode()}")
    check(not leaks, "no embedded private keys, GitHub tokens or client secrets" + (": " + "; ".join(leaks) if leaks else ""))

    lic = [n for n in names if n.startswith("assets/licenses/")]
    check("assets/licenses/SOURCES.txt" in lic and len(lic) >= 10, f"{len(lic)} license/source files bundled")

    sha = hashlib.sha256(open(apk, "rb").read()).hexdigest()
    print(f"\nAPK sha256 {sha}\n{len(names)} entries, {len(classes)} dex types")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
