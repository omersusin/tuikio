"""bsd-games 2.17 (the Linux port of the classic BSD games, with Debian's maintenance patches).

One host build with the system compiler generates everything the games need at build time
(adventure's cave data, atc's parser, boggle's dictionary, monop's card deck, phantasia's
monster file) and stages their data files. Each ABI then cross-compiles every game's objects,
as listed in the tree's own exec.objs and exec.libs, against those generated sources.

All data paths are relative ("./atc/", "./quiz/index", ...): the app starts every game with
its private home directory as working directory and installs the staged files there.
"""
import json
import os
import re
import shutil
import threading

from tk import (ASSETS, BUILD, CFLAGS, HERE, LEGACY, BuildError, apply_debian_patches, apply_patches,
                game, log, run, unpack, zig)

ORIG = "bsdgames_2.17.orig.tar.gz"
DEBIAN = "bsdgames_2.17-33.debian.tar.xz"
SCOWL = "scowl_2020.12.07.orig.tar.gz"

# catalog id -> (exec.objs target, data files staged for it). A data path ending in "/" is a
# directory the game writes into; it gets an empty ".keep" placeholder.
GAMES = {
    "adventure-bsd": ("adventure", []),
    "arithmetic-bsd": ("arithmetic", []),
    "atc-bsd": ("atc", ["atc/", "atc_score"]),
    "backgammon-bsd": ("backgammon/backgammon/backgammon", []),
    "teachgammon-bsd": ("backgammon/teachgammon/teachgammon", []),
    "battlestar-bsd": ("battlestar", ["battlestar.log"]),
    "boggle-bsd": ("boggle/boggle/boggle", ["boggle/dictionary", "boggle/dictindex", "boggle/helpfile"]),
    "canfield-bsd": ("canfield/canfield/canfield", ["cfscores"]),
    "cribbage-bsd": ("cribbage", ["cribbage.instr", "criblog"]),
    "dab-bsd": ("dab", []),
    "go-fish-bsd": ("fish", ["fish.instr"]),
    "gomoku-bsd": ("gomoku", []),
    "hack-bsd": ("hack", ["hack/data", "hack/help", "hack/hh", "hack/rumors", "hack/record", "hack/perm", "hack/save/"]),
    "hangman-bsd": ("hangman", ["words"]),
    "mille-bsd": ("mille", []),
    "monop-bsd": ("monop", ["monop-cards.pck"]),
    "phantasia-bsd": ("phantasia", ["phantasia/monsters", "phantasia/characs", "phantasia/gold", "phantasia/lastdead",
                                    "phantasia/mess", "phantasia/motd", "phantasia/scoreboard", "phantasia/void"]),
    "quiz-bsd": ("quiz", ["quiz/"]),
    "robots-bsd": ("robots", ["robots_roll"]),
    "sail-bsd": ("sail", ["saillog", "sail/"]),
    "snake-bsd": ("snake/snake/snake", ["snake.log", "snakerawscores"]),
    "tetris-bsd": ("tetris", ["tetris-bsd.scores"]),
    "trek-bsd": ("trek", []),
    "worm-bsd": ("worm", []),
    "wump-bsd": ("wump", ["wump.info"]),
}

NOT_BUILT = ("banner bcd caesar countmail dm factor fortune hunt morse number pom ppt primes rain random "
             "wargames worms wtf")

CONFIG = f"""bsd_games_cfg_non_interactive=y
bsd_games_cfg_install_prefix=`pwd`/stage/
bsd_games_cfg_no_build_dirs="{NOT_BUILT}"
bsd_games_cfg_gamesdir=/games
bsd_games_cfg_sbindir=/games
bsd_games_cfg_usrbindir=/games
bsd_games_cfg_usrlibdir=.
bsd_games_cfg_sharedir=.
bsd_games_cfg_varlibdir=.
bsd_games_cfg_do_chown=n
bsd_games_cfg_gzip_manpages=n
bsd_games_cfg_pager=more
bsd_games_cfg_dictionary_src=`pwd`/words
bsd_games_cfg_hangman_wordsfile=./words
bsd_games_cfg_use_libcrypto=n
bsd_games_cfg_optimize_flags="-O2"
bsd_games_cfg_other_cflags="-fcommon"
bsd_games_cfg_lex="flex --noyywrap"
bsd_games_cfg_lex_lib=""
"""

# musl has no <sys/cdefs.h>; the tree's own include/sys/cdefs.h wraps the system one.
COMPAT_INCLUDE = f"-idirafter {os.path.join(HERE, 'compat', 'include')}"

# Headers glibc pulls in implicitly but musl does not, and other per-directory fixes.
EXTRA = {
    # hack: its getdate() predates the POSIX one; PC and delay_output() would clash with ncurses.
    "hack": f"-include {os.path.join(HERE, 'ports', 'bsdgames', 'hack-getdate.h')} -DPC=hack_PC -Ddelay_output=hack_delay_output",
    "wump": "-include fcntl.h",
}

_host_lock = threading.Lock()
_host = {}


def word_list(dst):
    """The list Debian's wamerican package is made from (SCOWL size 50, American spelling),
    keeping only words of lower-case ASCII letters as hangman and boggle require."""
    src = unpack(SCOWL, os.path.join(os.path.dirname(dst), "scowl"))
    words = set()
    for kind in ("english-words", "american-words"):
        for size in (10, 20, 35, 40, 50):
            with open(os.path.join(src, "final", f"{kind}.{size}"), encoding="latin-1") as fh:
                words.update(w for w in (line.strip() for line in fh) if re.fullmatch(r"[a-z]+", w))
    with open(dst, "w") as fh:
        fh.write("\n".join(sorted(words)) + "\n")


def host_build():
    """Builds the tree once with the build machine's compiler; returns (tree, flags per dir)."""
    with _host_lock:
        if _host:
            return _host["src"], _host["flags"]
        log("bsdgames: host build (generated sources and data)")
        work = os.path.join(BUILD, "host", "bsdgames")
        src = unpack(ORIG, os.path.join(work, "src"))
        apply_debian_patches(src, DEBIAN)
        apply_patches(src, "bsdgames")
        word_list(os.path.join(src, "words"))
        with open(os.path.join(src, "config.params"), "w") as fh:
            fh.write(CONFIG)
        run("./configure", src)
        run("make -j4", src)
        # adventure's text is XOR-ed with random() when data.c is generated and decoded with
        # random() at run time: generate it with musl's random(), which the games use, rather
        # than the host C library's different sequence.
        run(f"{zig()} cc -target x86_64-linux-musl -static -O2 -Iinclude -Iadventure {COMPAT_INCLUDE} "
            "adventure/setup.c -o adventure/setup-musl && adventure/setup-musl adventure/glorkz > adventure/data.c", src)
        run("make install", src)
        # Per-directory compiler flags, straight from the generated makefile.
        with open(os.path.join(src, "print.mk"), "w") as fh:
            fh.write("print-%:\n\t@echo '$*=$($*)'\n")
        dirs = {"lib", "backgammon/common_source"}
        for target, _ in GAMES.values():
            dirs.add(target_dir(target))
        names = [f"print-{v}" for d in sorted(dirs) for v in (f"{key(d)}_CFLAGS", f"{key(d)}_DEFS", f"{key(d)}_INCS")]
        run(f"make -s -f GNUmakefile -f print.mk print-BASE_INCS {' '.join(names)} > flags.txt", src)
        values = dict(line.rstrip("\n").split("=", 1) for line in open(os.path.join(src, "flags.txt")) if "=" in line)
        flags = {d: f"{values['BASE_INCS']} -I{d} " + " ".join(values.get(f"{key(d)}_{v}", "") for v in ("CFLAGS", "DEFS", "INCS"))
                 for d in dirs}
        stage = os.path.join(src, "stage")
        for gid, (_, data) in GAMES.items():
            for rel in data:
                stage_asset(gid, stage, rel)
        with open(os.path.join(work, "flags.json"), "w") as fh:
            json.dump(flags, fh, indent=1)
        _host.update(src=src, flags=flags)
        return src, flags


def stage_asset(gid, stage, rel):
    dst_root = os.path.join(ASSETS, "games", gid)
    if rel.endswith("/"):
        path = os.path.join(stage, rel)
        files = sorted(f for f in os.listdir(path) if os.path.isfile(os.path.join(path, f))) if os.path.isdir(path) else []
        if not files:  # a directory the game writes into
            os.makedirs(os.path.join(dst_root, rel), exist_ok=True)
            open(os.path.join(dst_root, rel, ".keep"), "w").close()
        for f in files:
            os.makedirs(os.path.join(dst_root, rel), exist_ok=True)
            shutil.copyfile(os.path.join(path, f), os.path.join(dst_root, rel, f))
        return
    path = os.path.join(stage, rel)
    if rel == "words":
        path = os.path.join(stage, "..", "words")  # hangman reads the word list at run time
    if not os.path.exists(path) and rel.startswith("hack/"):
        path = os.path.join(stage, "usr/share/games/bsdgames", rel)  # installed by the Debian makefile
    os.makedirs(os.path.dirname(os.path.join(dst_root, rel)), exist_ok=True)
    if os.path.exists(path):
        shutil.copyfile(path, os.path.join(dst_root, rel))
    elif rel in ("hack/record", "hack/perm"):
        open(os.path.join(dst_root, rel), "w").close()
    else:
        raise BuildError(f"bsdgames: staged file {rel} is missing")


def key(d):
    return d.replace("/", "_")


def target_dir(target):
    return os.path.dirname(target) if "/" in target else target


def exec_table(src, name):
    """Parses exec.objs / exec.libs: target -> list of words (continuation lines start with a tab)."""
    table, current = {}, None
    for line in open(os.path.join(src, name)):
        if line.startswith("#") or not line.strip():
            continue
        if line[0] in " \t" and current:
            table[current] += line.replace("\\", " ").split()
        else:
            parts = line.replace("\\", " ").split()
            current = parts[0]
            table[current] = parts[1:]
    return table


def cross_build(abi, gid):
    src, flags = host_build()
    target = GAMES[gid][0]
    tdir = target_dir(target)
    objs_table, libs_table = exec_table(src, "exec.objs"), exec_table(src, "exec.libs")
    work = os.path.join(abi.work("bsdgames"), gid)
    os.makedirs(work, exist_ok=True)
    objects, cxx = [], False
    for o in objs_table[target]:
        rel = o if "/" in o else os.path.join(tdir, o)
        stem = rel[:-2]
        for ext in (".c", ".cc"):
            if os.path.exists(os.path.join(src, stem + ext)):
                source = stem + ext
                break
        else:
            raise BuildError(f"bsdgames: no source for {rel}")
        is_cxx = source.endswith(".cc")
        cxx = cxx or is_cxx
        out = os.path.join(work, key(stem) + ".o")
        compiler = abi.cxx if is_cxx else abi.cc
        lang = "-std=gnu++98" if is_cxx else LEGACY
        lang += " " + EXTRA.get(os.path.dirname(source), "")
        run(f"{compiler} {CFLAGS} {lang} -D_GNU_SOURCE {abi.curses_cflags} {flags[os.path.dirname(source)]} {COMPAT_INCLUDE} "
            f"-c {source} -o {out}", src, logname=f"build-{abi.name}-{gid}.log")
        objects.append(out)
    libs = " ".join(libs_table.get(target, [])).replace("$(NCURSES_LIB)", abi.curses_libs).replace("$(LEX_LIB)", "")
    exe = os.path.join(work, os.path.basename(target))
    run(f"{abi.cxx if cxx else abi.cc} {' '.join(objects)} -o {exe} {abi.ldflags} {libs}", work)
    return {"game_" + gid.removesuffix("-bsd").replace("-", "_") + "_bsd": exe}


for _gid in GAMES:
    game(_gid, notice=(ORIG, "COPYING"), license_name="bsdgames")(lambda abi, _g=_gid: cross_build(abi, _g))
