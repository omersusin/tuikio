"""Single-game source packages from the Ubuntu 24.04 archive (pinned in native/sources.lock.json
from the archive's signed index). Data paths point into the game's home, its working directory."""
import glob
import os
import shutil

from tk import (LEGACY, BuildError, apply_debian_patches, autotools, cc, game, install_asset, placeholder, replace_in,
                run, unpack)


def tree(abi, gid, archive, debian=None, skip=()):
    src = unpack(archive, os.path.join(abi.work(gid), "src"))
    if debian:
        apply_debian_patches(src, debian, skip)
    return src


def files(src, *patterns, exclude=()):
    out = []
    for p in patterns:
        out += [f for f in sorted(glob.glob(os.path.join(src, p))) if os.path.basename(f) not in exclude]
    if not out:
        raise BuildError(f"no sources match {patterns} in {src}")
    return out


def assets(gid, src, rel, dest=None):
    """Stages a data file or every file under a directory as assets/games/<gid>/<dest>."""
    path = os.path.join(src, rel)
    dest = dest if dest is not None else rel
    if os.path.isdir(path):
        for dirpath, _, names in os.walk(path):
            for n in names:
                full = os.path.join(dirpath, n)
                install_asset(f"games/{gid}/{dest}/{os.path.relpath(full, path)}", full)
    else:
        install_asset(f"games/{gid}/{dest}", path)


@game("tty-solitaire", notice=("tty-solitaire_1.3.1.orig.tar.gz", "LICENSE"))
def tty_solitaire(abi):
    src = tree(abi, "tty-solitaire", "tty-solitaire_1.3.1.orig.tar.gz")
    exe = os.path.join(src, "ttysolitaire")
    cc(abi, files(src, "src/*.c"), exe, '-std=c99 -D_XOPEN_SOURCE=700 -DVERSION=\\"1.3.1\\" -fcommon', abi.curses_libs)
    return {"game_tty_solitaire": exe}


@game("tint", notice=("tint_0.07.tar.xz", "debian/copyright"))
def tint(abi):
    src = tree(abi, "tint", "tint_0.07.tar.xz")
    exe = os.path.join(src, "tint")
    cc(abi, files(src, "engine.c", "utils.c", "io.c", "tint.c"), exe,
       f'{LEGACY} -DSCOREFILE=\\"tint.scores\\"', abi.curses_libs)
    return {"game_tint": exe}


@game("pacman4console", notice=("pacman4console_1.3.orig.tar.gz", "COPYING"))
def pacman4console(abi):
    src = tree(abi, "pacman4console", "pacman4console_1.3.orig.tar.gz", "pacman4console_1.3-1build3.debian.tar.xz")
    exe = os.path.join(src, "pacman4console")
    # Its functions are used before they are defined; none returns a pointer, so implicit
    # declarations are harmless here.
    cc(abi, [os.path.join(src, "pacman.c")], exe, f'{LEGACY} -Wno-error=implicit-function-declaration -DDATAROOTDIR=\\".\\"',
       abi.curses_libs)
    assets("pacman4console", src, "Levels", "pacman/Levels")
    return {"game_pacman4console": exe}


@game("sudoku", notice=("sudoku_1.0.5.orig.tar.gz", "README"))
def sudoku(abi):
    src = tree(abi, "sudoku", "sudoku_1.0.5.orig.tar.gz", "sudoku_1.0.5-2.1.debian.tar.xz")
    exe = os.path.join(src, "sudoku")
    replace_in(os.path.join(src, "sudoku.c"), '#define TEMPLATE "/usr/share/sudoku/template"', '#define TEMPLATE "template"')
    # musl's FILE is opaque, and the program only ever uses this one's address as a marker.
    replace_in(os.path.join(src, "sudoku.c"), "static FILE default_template_file;",
               "static char default_template_marker;\n#define default_template_file (*(FILE *)&default_template_marker)")
    cc(abi, [os.path.join(src, "sudoku.c")], exe, LEGACY, abi.curses_libs)
    assets("sudoku", src, "template")
    return {"game_sudoku": exe}


@game("curseofwar", notice=("curseofwar_1.1.8.orig.tar.gz", "LICENSE"))
def curseofwar(abi):
    src = tree(abi, "curseofwar", "curseofwar_1.1.8.orig.tar.gz", "curseofwar_1.1.8-3.1.debian.tar.xz")
    exe = os.path.join(src, "curseofwar")
    cc(abi, files(src, "*.c", exclude=("main-sdl.c", "output-sdl.c", "path.c")), exe, "-std=gnu99", f"{abi.curses_libs} -lm")
    return {"game_curseofwar": exe}


@game("cavezofphear", notice=("cavezofphear_0.5.1.orig.tar.gz", "LICENSE"))
def cavezofphear(abi):
    src = tree(abi, "cavezofphear", "cavezofphear_0.5.1.orig.tar.gz", "cavezofphear_0.5.1-1.1.debian.tar.xz")
    exe = os.path.join(src, "phear")
    cc(abi, files(src, "src/*.c"), exe, f"{LEGACY} -I{src}/src", abi.curses_libs)
    assets("cavezofphear", src, "data")
    return {"game_cavezofphear": exe}


@game("empire", notice=("empire_1.16.orig.tar.gz", "COPYING"))
def empire(abi):
    src = tree(abi, "empire", "empire_1.16.orig.tar.gz", "empire_1.16-2.debian.tar.xz")
    exe = os.path.join(src, "vms-empire")
    cc(abi, files(src, "*.c"), exe, f'{LEGACY} -DVERSION=\\"1.16\\"', abi.curses_libs)
    return {"game_empire": exe}


@game("petris", notice=("petris_1.0.1.orig.tar.gz", "README"))
def petris(abi):
    src = tree(abi, "petris", "petris_1.0.1.orig.tar.gz", "petris_1.0.1-11.debian.tar.xz")
    replace_in(os.path.join(src, "config.h"), '"/var/games/petris.scores"', '"petris.scores"')
    exe = os.path.join(src, "petris")
    cc(abi, files(src, "*.c"), exe, LEGACY, abi.curses_libs)
    return {"game_petris": exe}


@game("bombardier", notice=("bombardier_0.8.3+nmu3.tar.xz", "COPYING"))
def bombardier(abi):
    src = tree(abi, "bombardier", "bombardier_0.8.3+nmu3.tar.xz")
    replace_in(os.path.join(src, "hof.c"), '"/var/games/bombardier/bdscore"', '"bdscore"', count=0)
    exe = os.path.join(src, "bombardier")
    objs = "bombardier display date randomhouse step hof signal gcurses".split()  # the Makefile's OBJS
    cc(abi, [os.path.join(src, f"{o}.c") for o in objs], exe, LEGACY, abi.curses_libs)
    return {"game_bombardier": exe}


@game("netris", notice=("netris_0.52.orig.tar.gz", "COPYING"))
def netris(abi):
    src = tree(abi, "netris", "netris_0.52.orig.tar.gz", "netris_0.52-11.debian.tar.xz")
    # What ./Configure generates: config.h, version.c and proto.h (the ExtFunc prototypes).
    with open(os.path.join(src, "config.h"), "w") as fh:
        fh.write("#include <getopt.h>\n#include <memory.h>\n#define HAS_SIGPROCMASK\n")
    version = open(os.path.join(src, "VERSION")).read().strip()
    with open(os.path.join(src, "version.c"), "w") as fh:
        fh.write(f'char *version_string = "{version}";\n')
    sources = files(src, "game.c", "curses.c", "shapes.c", "board.c", "util.c", "inet.c", "robot.c")
    with open(os.path.join(src, "proto.h"), "w") as fh:
        for f in sources:
            for line in open(f, encoding="latin-1"):
                if line.startswith(("ExtFunc ", "ExtFunc\t")):
                    line = line.rstrip("\n")
                    fh.write((line[:-1] + ");" if line.endswith(")") else line) + "\n")
    exe = os.path.join(src, "netris")
    cc(abi, sources + [os.path.join(src, "version.c")], exe, f"{LEGACY} -I{src}", abi.curses_libs)
    return {"game_netris": exe}


@game("nudoku", notice=("nudoku_2.1.0.orig.tar.gz", "LICENSE"))
def nudoku(abi):
    src = tree(abi, "nudoku", "nudoku_2.1.0.orig.tar.gz", "nudoku_2.1.0-0.2.debian.tar.xz")
    exe = os.path.join(src, "nudoku")
    # What its autotools build would pass, without gettext (ENABLE_NLS unset) or cairo.
    cc(abi, files(src, "src/main.c", "src/sudoku.c"), exe,
       f'-std=gnu99 -DVERSION=\\"2.1.0\\" -DPACKAGE=\\"nudoku\\" -DLOCALEDIR=\\".\\" -I{src}/src', abi.curses_libs)
    return {"game_nudoku": exe}


@game("typespeed", notice=("typespeed_0.6.5.orig.tar.gz", "COPYING"))
def typespeed(abi):
    src = tree(abi, "typespeed", "typespeed_0.6.5.orig.tar.gz", "typespeed_0.6.5-4.debian.tar.xz")
    # Word lists, rules and the config file are read from the game's home.
    # (autoconf only takes absolute directories; the relative ones go to make)
    autotools(abi, src, "--disable-nls", make="make -j4 datadir=. sysconfdir=. highscoredir=.")
    for kind in ("words", "rules"):
        for f in sorted(os.listdir(os.path.join(src, kind))):
            if f.startswith(("words.", "rule.")):
                install_asset(f"games/typespeed/typespeed/{kind}/{f}", os.path.join(src, kind, f))
    install_asset("games/typespeed/typespeedrc", os.path.join(src, "etc", "typespeedrc"))
    placeholder("games/typespeed/typespeed.score")  # it refuses to start without one
    return {"game_typespeed": os.path.join(src, "src", "typespeed")}


@game("gtypist", notice=("gtypist_2.9.5.orig.tar.gz", "COPYING"))
def gtypist(abi):
    src = tree(abi, "gtypist", "gtypist_2.9.5.orig.tar.gz", "gtypist_2.9.5-3build1.debian.tar.xz")
    # A few of its own functions are used undeclared; all return int or void, so it's harmless.
    autotools(abi, src, "--disable-nls", cflags="-Wno-error=implicit-function-declaration", make="make -j4 -C src datadir=.")
    for f in sorted(os.listdir(os.path.join(src, "lessons"))):
        if f.endswith(".typ"):
            install_asset(f"games/gtypist/gtypist/{f}", os.path.join(src, "lessons", f))
    return {"game_gtypist": os.path.join(src, "src", "gtypist")}


@game("trader", notice=("trader_7.18.orig.tar.xz", "COPYING"))
def trader(abi):
    src = tree(abi, "trader", "trader_7.18.orig.tar.xz", "trader_7.18-1.debian.tar.xz")
    autotools(abi, src, "--disable-nls")
    return {"game_trader": os.path.join(src, "src", "trader")}


@game("nettoe", notice=("nettoe_1.5.1.orig.tar.gz", "COPYING"))
def nettoe(abi):
    src = tree(abi, "nettoe", "nettoe_1.5.1.orig.tar.gz", "nettoe_1.5.1-3.debian.tar.xz")
    autotools(abi, src)
    return {"game_nettoe": os.path.join(src, "src", "nettoe")}


@game("moria", notice=("moria_5.7.13.orig.tar.gz", "LICENSE"))
def moria(abi):
    src = tree(abi, "moria", "moria_5.7.13.orig.tar.gz", "moria_5.7.13-1.debian.tar.xz")
    replace_in(os.path.join(src, "src", "config.cpp"), '"/var/games/moria/scores.dat"', '"scores.dat"')
    exe = os.path.join(src, "umoria")
    cc(abi, files(src, "src/*.cpp"), exe, f"-std=c++14 -I{src}/src {abi.curses_cflags}", abi.curses_libs, cxx=True)
    # data/ is read relative to the working directory, the game's home.
    for f in sorted(os.listdir(os.path.join(src, "data"))):
        path = os.path.join(src, "data", f)
        if f.endswith(".in"):  # what CMake's configure_file() does
            text = open(path).read().replace("${umoria_version}", "5.7.13").replace("${current_date}", "2020-09-04")
            out = path[:-3]
            with open(out, "w") as fh:
                fh.write(text)
            path, f = out, f[:-3]
        if f == "scores.dat":
            placeholder("games/moria/scores.dat")  # high scores: created once, kept across updates
        else:
            install_asset(f"games/moria/data/{f}", path)
    return {"game_moria": exe}


@game("chroma", notice=("chroma_1.19-1ubuntu1.tar.gz", "COPYING"))
def chroma(abi):
    src = tree(abi, "chroma", "chroma_1.19-1ubuntu1.tar.gz")
    autotools(abi, src, "--enable-curses --disable-sdl", make="make -j4 datadir=. chroma-curses")
    for d in ("colours", "help", "levels", "locale"):
        for dirpath, _, names in os.walk(os.path.join(src, d)):
            if "/levels/regression" in dirpath:
                continue
            for n in names:
                full = os.path.join(dirpath, n)
                install_asset(f"games/chroma/chroma/{os.path.relpath(full, src)}", full)
    return {"game_chroma": os.path.join(src, "chroma-curses")}
