"""The first ports: single-game sources from the Ubuntu archive and GitHub, plus Tuikio's own
Lantern Tide."""
import os

from tk import (CFLAGS, HERE, LEGACY, apply_patches, autotools, cc, fetch, game,
                install_asset, run, unpack)


@game("lantern-tide", curses=False)
def lantern_tide(abi):
    exe = os.path.join(abi.work("lantern-tide"), "lantern_tide")
    cc(abi, [os.path.join(HERE, "original", "lantern-tide", "lantern_tide.c")], exe, "-std=c11 -Wall -Wextra -Werror")
    return {"game_lantern_tide": exe}


@game("2048", curses=False, notice=("2048-LICENSE", None))
def g2048(abi):
    exe = os.path.join(abi.work("2048"), "game2048")
    cc(abi, [fetch("2048.c")], exe, "-std=c99 -D_XOPEN_SOURCE=700")
    return {"game_2048": exe}


@game("vitetris", curses=False, notice=("vitetris_0.59.1.orig.tar.gz", "licence.txt"))
def vitetris(abi):
    src = unpack("vitetris_0.59.1.orig.tar.gz", os.path.join(abi.work("vitetris"), "src"))
    apply_patches(src, "vitetris")
    run("./configure --without-x --without-network --without-joystick --without-curses "
        "--enable-2player --enable-term_resizing --enable-menu --enable-blockstyles "
        "--prefix=/ ", src, abi.env())
    run(f'make -j4 CC="{abi.cc}" CFLAGS="{CFLAGS} {LEGACY}" LDFLAGS="{abi.ldflags}"', src, abi.env())
    return {"game_vitetris": os.path.join(src, "tetris")}


@game("robotfindskitten", notice=("robotfindskitten_2.8284271.702.orig.tar.gz", "COPYING"))
def robotfindskitten(abi):
    src = unpack("robotfindskitten_2.8284271.702.orig.tar.gz", os.path.join(abi.work("robotfindskitten"), "src"))
    # Messages ship as data (assets/games/robotfindskitten/), installed to ~/.robotfindskitten.
    autotools(abi, src, cflags='-DSYSTEM_NKI_DIR=\\".robotfindskitten\\"')
    install_asset("games/robotfindskitten/vanilla.nki", os.path.join(src, "nki", "vanilla.nki"))
    return {"game_robotfindskitten": os.path.join(src, "src", "robotfindskitten")}


@game("moon-buggy", notice=("moon-buggy_1.0.51.orig.tar.gz", "COPYING"))
def moon_buggy(abi):
    src = unpack("moon-buggy_1.0.51.orig.tar.gz", os.path.join(abi.work("moon-buggy"), "src"))
    apply_patches(src, "moon-buggy")  # leaving the app pauses a run instead of ending it
    autotools(abi, src, "--sharedstatedir=/nonexistent")
    # Rebuild highscore.o with the score file in the game's own directory.
    run(f'make highscore.o scoredir=. -B CFLAGS="{CFLAGS} {LEGACY}" && make', src,
        abi.env(LIBS=abi.curses_libs, LDFLAGS=f"{abi.ldflags} -L{abi.sysroot}/lib"))
    return {"game_moonbuggy": os.path.join(src, "moon-buggy")}


@game("freesweep", notice=("freesweep_1.0.2.orig.tar.gz", "COPYING"))
def freesweep(abi):
    src = unpack("freesweep_1.0.2.orig.tar.gz", os.path.join(abi.work("freesweep"), "src"))
    autotools(abi, src, "--with-scoresdir=. --sysconfdir=/nonexistent")
    return {"game_freesweep": os.path.join(src, "freesweep")}


@game("ninvaders", notice=("ninvaders_0.1.1.orig.tar.gz", "gpl.txt"))
def ninvaders(abi):
    src = unpack("ninvaders_0.1.1.orig.tar.gz", os.path.join(abi.work("ninvaders"), "src"))
    srcs = [os.path.join(src, f) for f in ("globals.c", "view.c", "aliens.c", "ufo.c", "player.c", "nInvaders.c")]
    exe = os.path.join(src, "nInvaders")
    cc(abi, srcs, exe, f"-I{src} {LEGACY}", abi.curses_libs)
    return {"game_ninvaders": exe}


@game("greed", notice=("greed_4.2.orig.tar.gz", "COPYING"))
def greed(abi):
    src = unpack("greed_4.2.orig.tar.gz", os.path.join(abi.work("greed"), "src"))
    exe = os.path.join(src, "greed")
    # Scores and their lock file live next to the game's saves, not in /var or /tmp.
    cc(abi, [os.path.join(src, "greed.c")], exe,
       f'{LEGACY} -DSCOREFILE=\\".greedscores\\" -DLOCKPATH=\\".greed.lock\\" -DRELEASE=\\"4.2\\"',
       abi.curses_libs)
    return {"game_greed": exe}


@game("nsnake", notice=("nsnake_3.0.1.orig.tar.gz", "COPYING"))
def nsnake(abi):
    src = unpack("nsnake_3.0.1.orig.tar.gz", os.path.join(abi.work("nsnake"), "src"))
    srcs = []
    for root, _, files in os.walk(src):
        srcs += [os.path.join(root, f) for f in files if f.endswith(".cpp") and "/doc" not in root]
    objs = []
    for root, _, files in os.walk(os.path.join(src, "deps")):
        for f in files:
            if f.endswith(".c"):
                obj = os.path.join(root, f[:-2] + ".o")
                run(f"{abi.cc} {CFLAGS} -c {os.path.join(root, f)} -o {obj}", src)
                objs.append(obj)
    exe = os.path.join(src, "nsnake")
    cc(abi, sorted(srcs) + objs, exe,
       f'-I{src}/src -I{src}/deps -DVERSION=\\"3.0.1\\" -DDATE=\\"Mar2016\\" -DPACKAGE=\\"nsnake\\" -DSYSTEM_LEVEL_DIR=\\"levels\\" -std=c++11',
       abi.curses_libs, cxx=True)
    return {"game_nsnake": exe}
