#!/usr/bin/env python3
"""Writes catalog/games/<id>.json for the ports described in tools/ports/families/*.py, and links
each one to its catalog/discovery.json entry (adding one if the game wasn't listed yet).

Data files are read from the native build (native/build/out/assets/games/<id>/), so run
python3 native/build.py first; when a game's assets are missing, its existing data_files are kept.

    python3 tools/ports/manifests.py                  # every family
    python3 tools/ports/manifests.py bsdgames         # one family
"""
import hashlib
import importlib.util
import json
import os
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
FAMILIES = os.path.join(ROOT, "tools", "ports", "families")
ASSETS = os.path.join(ROOT, "native", "build", "out", "assets")
GAMES = os.path.join(ROOT, "catalog", "games")
DISCOVERY = os.path.join(ROOT, "catalog", "discovery.json")


def sha256(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def load(name):
    spec = importlib.util.spec_from_file_location(f"family_{name}", os.path.join(FAMILIES, f"{name}.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def data_files(gid, previous):
    root = os.path.join(ASSETS, "games", gid)
    if not os.path.isdir(root):
        return previous
    out = []
    for dirpath, _, files in os.walk(root):
        for f in files:
            path = os.path.join(dirpath, f)
            rel = os.path.relpath(path, root).replace(os.sep, "/")
            out.append({"asset": f"games/{gid}/{rel}", "dest": rel, "sha256": sha256(path)})
    return sorted(out, key=lambda d: d["dest"])


def manifest(fam, g, lock):
    d = dict(fam.DEFAULTS)
    d.update(g)
    path = os.path.join(GAMES, f"{d['id']}.json")
    old = json.load(open(path)) if os.path.exists(path) else {}
    archive = d.get("archive")
    term_min, term_pref = d["terminal"]
    port = {
        "maintainer": d.get("maintainer", "Tuikio ports team"),
        "recipe": d["recipe"],
        "source_archive": archive,
        "source_sha256": lock[archive]["sha256"] if archive else None,
    }
    if d.get("license_file"):
        port["license_file"] = d["license_file"]
    saves, save_notes = d.get("saves", ([], "No saves."))
    return {
        "schema_version": 1,
        "id": d["id"],
        "title": d["title"],
        "summary": d["summary"],
        "description": d["description"],
        "category": d["category"],
        "tags": d["tags"],
        "players": d.get("players", "1"),
        "origin": d.get("origin", "verified_port"),
        "upstream": {
            "authors": d["authors"],
            "license": d["license"],
            "source_url": d["source_url"],
            "version": d.get("version"),
        },
        "port": port,
        "runtime": {
            "backend": "native_pty",
            "executable": d["exe"],
            "args": d.get("args", []),
            "env": d.get("env", {}),
            "terminal": {"min": list(term_min), "preferred": list(term_pref)},
            "orientation": d.get("orientation", "landscape"),
        },
        "controls": d["controls"],
        "saves": {"paths": saves, "notes": save_notes},
        "data_files": data_files(d["id"], old.get("data_files", [])),
        "network": d.get("network", "none"),
        "status": "PORTABLE",
        "verification": {
            "host_tested": d["host_tested"],
            "android_tested": old.get("verification", {}).get("android_tested"),
            "notes": d["test_notes"],
        },
    }


def link_discovery(entries, m, d):
    """Marks the game's discovery entry as ported (or adds one)."""
    target = d.get("discovery") or m["id"]
    entry = next((e for e in entries if e["id"] == target), None)
    if entry is None:
        entry = {
            "id": target, "title": m["title"], "source_url": d.get("discovery_url", m["upstream"]["source_url"]),
            "kind": "game", "category": m["category"], "status": "CANDIDATE", "upstream_license": "UNKNOWN",
            "gameplay_actions": [], "expected_terminal_size": [], "preferred_orientation": None,
            "control_profile": None, "backend": "undecided", "platform_support": None,
            "import_date": m["verification"]["host_tested"], "last_checked": None, "version": None,
            "dependencies": [], "screenshot_provenance": None, "build_recipe": None, "game_id": None, "notes": "",
        }
        entries.append(entry)
    entry.update({
        "category": m["category"],
        "status": "PORTABLE",
        "upstream_license": m["upstream"]["license"],
        "backend": "native_pty",
        "last_checked": m["verification"]["host_tested"],
        "build_recipe": m["port"]["recipe"],
        "game_id": m["id"],
        "notes": d.get("discovery_notes", "Ported to Tuikio; see the game manifest."),
    })


def main():
    names = sys.argv[1:] or sorted(f[:-3] for f in os.listdir(FAMILIES) if f.endswith(".py") and not f.startswith("_"))
    lock = json.load(open(os.path.join(ROOT, "native", "sources.lock.json")))["files"]
    entries = json.load(open(DISCOVERY))
    count = 0
    for name in names:
        fam = load(name)
        for g in fam.GAMES:
            d = dict(fam.DEFAULTS)
            d.update(g)
            m = manifest(fam, g, lock)
            with open(os.path.join(GAMES, f"{m['id']}.json"), "w") as fh:
                json.dump(m, fh, indent=2, ensure_ascii=False)
                fh.write("\n")
            link_discovery(entries, m, d)
            count += 1
    with open(DISCOVERY, "w") as fh:
        json.dump(entries, fh, indent=2, ensure_ascii=False)
        fh.write("\n")
    print(f"wrote {count} manifests")


if __name__ == "__main__":
    main()
