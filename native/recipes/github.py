"""Single games from GitHub, pinned by commit (tools/ports/git_lock.py). Each entry lists what
the upstream makefile compiles; paths that pointed at /var, /tmp or /usr are redirected into
the game's home (its working directory) with exact, checked substitutions."""
import glob
import os

from tk import HERE, LEGACY, BuildError, autotools, cc, game, install_asset, placeholder, replace_in, run, unpack


def entry(lock, sources, exe, *, flags="", libs="curses", cxx=False, notice="LICENSE", subst=(), data=(),
          placeholders=(), build=None):
    return dict(lock=lock, sources=sources, exe=exe, flags=flags, libs=libs, cxx=cxx, notice=notice, subst=subst,
                data=data, placeholders=placeholders, build=build)


TMP = ('"/tmp/', '".tmp/')  # the app gives every game a private .tmp in its home
# Stand-ins for headers musl lacks (gettext.h with NLS off, termio.h); a tree's own copy wins.
COMPAT = f"-idirafter {os.path.join(HERE, 'compat', 'include')}"

GAMES = {
    "ascii-invaders": entry("ascii-invaders-05685db4.tar", ["invaders.c"], "game_ascii_invaders", flags="-std=gnu99"),
    "unix-aliens": entry("unix-aliens-94b5eb2e.tar", ["aliens.c"], "game_unix_aliens", notice="UNLICENSE",
                         subst=[("aliens.c", '"/var/games/aliens.score"', '"aliens.score"')]),
    "ascii-pacman": entry("ascii-pacman-196fd6a9.tar", ["src/*.c"], "game_ascii_pacman", flags="-std=gnu99"),
    "tornado": entry("tornado-1148312f.tar", ["main.c", "draw.c", "erwin.c", "network.c", "scores.c"], "game_tornado",
                     notice="COPYING", flags=f'{LEGACY} -DPREFIX=\\".\\"',
                     subst=[("scores.h", '"/var/games/tornado.scores"', '"tornado.scores"')]),
    "xonitix": entry("xonitix-8d0eba81.tar", ["xonitix.cpp"], "game_xonitix", cxx=True, flags="-std=c++17"),
    "2048-cli": entry("2048-cli-67439255.tar", ["src/ai.c", "src/engine.c", "src/highscore.c", "src/main.c",
                                                "src/options.c", "src/merge_std.c", "src/gfx_curses.c"],
                      "game_2048_cli", flags=f"-std=gnu99 -Wno-incompatible-pointer-types -DINVERT_COLORS -DVT100 {COMPAT}"),
    "threes-c": entry("threes-c-369a0087.tar", ["src/threes.c"], "game_threes", flags="-Iinclude", libs="-lm"),
    # Its int functions mostly end without a return; C++ makes that undefined and clang turns it
    # into "unreachable", which crashed at start. -fno-strict-return keeps plain returns.
    "tinytetris": entry("tinytetris-38d4cc33.tar", ["tinytetris.cpp"], "game_tinytetris", cxx=True,
                        flags="-fno-strict-return"),
    "ctris": entry("ctris-9db20c4a.tar", ["ctris.c", "game.c", "screen.c", "brick.c", "highscore.c"], "game_ctris",
                   notice="COPYING", flags=LEGACY, libs="curses -lm", build="ctris"),
    "ascii-tetris": entry("ascii-tetris-f983b048.tar", ["main.c", "tetris.c"], "game_ascii_tetris", libs=""),
    "tinycols": entry("tinycols-9ad7a26f.tar", ["src/tinycols/*.c", "src/queue.c", "src/gfx.c", "src/main.c"], "game_tinycols",
                      flags="-std=gnu99 -DLINUX_TARGET -Iinclude -Ilib"),
    "ascii-snake": entry("ascii-snake-3107faaa.tar", ["snake.c"], "game_ascii_snake", notice="COPYING", libs=""),
    "tetris-berk76": entry("tetris-31d441a8.tar", ["src/main_con.c", "src/tetris.c", "src/shape.c", "src/tui_con.c",
                                                   "src/wait_con.c"], "game_tetris_berk76", flags=f"{LEGACY} -include time.h"),
    "nuzzle": entry("nuzzle-7f2822dd.tar", [], "game_nuzzle", build="nuzzle"),
    "morpion-solitaire": entry("morpion-solitaire-187d2c68.tar", ["main.c", "game.c", "ui.c", "export.c", "utils.c",
                                                                  "points.c", "highscore.c"], "game_morpion",
                               flags=LEGACY, libs="curses -lm"),
    "ascii-chess": entry("ascii-chess-f8cb9f10.tar", ["src/chess3.c"], "game_ascii_chess", notice="COPYING",
                         flags=LEGACY, libs=""),
    "wander": entry("wander-d3f07807.tar", [], "game_wander", build="wander"),
    "hamurabi": entry("hamurabi-994563d7.tar", ["src/main.c", "src/city.c"], "game_hamurabi", notice="COPYING",
                      flags='-Iheaders -DPACKAGE_STRING=\\"hamurabi\\"', libs="-lm"),
    "msnake": entry("msnake-830967fc.tar", ["src/*.c"], "game_msnake", flags="-std=gnu99 -Isrc"),
    "clines": entry("clines-410ae26c.tar", [], "game_clines", notice="COPYING", build="clines"),
    "termrex": entry("termrex-18d381ad.tar", ["*.cpp"], "game_termrex", cxx=True, flags="-std=c++17 -I."),
    "trog": entry("trog-b0a88433.tar", ["src/*.cpp"], "game_trog", cxx=True, flags="-std=c++11 -Iinclude"),
    "csol": entry("csol-b3bd1938.tar", ["src/*.c"], "game_csol", flags="-std=gnu99",
                  data=[("csolrc", "csolrc"), ("games", "games"), ("themes", "themes")]),
    "freecell-ng": entry("freecell-ng-e1d0f912.tar", [], "game_freecell_ng", notice="COPYING", build="autotools"),
    "ularn": entry("ularn-ef421842.tar", [], "game_ularn", build="ularn"),
}


def sources(src, patterns):
    out = []
    for p in patterns:
        out += sorted(glob.glob(os.path.join(src, p)))
    if not out:
        raise BuildError(f"no sources match {patterns}")
    return out


def stage(gid, src, rel, dest):
    path = os.path.join(src, rel)
    if os.path.isdir(path):
        for dirpath, _, names in os.walk(path):
            for n in names:
                full = os.path.join(dirpath, n)
                install_asset(f"games/{gid}/{dest}/{os.path.relpath(full, path)}", full)
    else:
        install_asset(f"games/{gid}/{dest}", path)


def build(abi, gid):
    e = GAMES[gid]
    src = unpack(e["lock"], os.path.join(abi.work(gid), "src"))
    for rel, old, new in e["subst"]:
        replace_in(os.path.join(src, rel), old, new)
    libs = e["libs"].replace("curses", abi.curses_libs)
    if e["build"]:
        exe = SPECIAL[e["build"]](abi, gid, src)
    else:
        exe = os.path.join(src, e["exe"])
        cc(abi, sources(src, e["sources"]), exe, f"-I{src} {e['flags']}", libs, cxx=e["cxx"], cwd=src)
    for rel, dest in e["data"]:
        stage(gid, src, rel, dest)
    for rel in e["placeholders"]:
        placeholder(f"games/{gid}/{rel}")
    return {e["exe"]: exe}


def build_ctris(abi, gid, src):
    open(os.path.join(src, "config.h"), "w").close()  # what create_config.sh writes on Linux
    # A reproducible build has no build date to show; use the pinned commit's date.
    replace_in(os.path.join(src, "ctris.c"), '", VERSION, __DATE__);', '", VERSION, "2025-01-07");')
    exe = os.path.join(src, "game_ctris")
    cc(abi, sources(src, GAMES[gid]["sources"]), exe, f"-I{src} {LEGACY}", f"{abi.curses_libs} -lm", cwd=src)
    return exe


def build_nuzzle(abi, gid, src):
    # on_exit() is glibc-only; its callback takes no arguments here, so atexit() is the same.
    replace_in(os.path.join(src, "src", "nuzzle.c"), "on_exit(exit_callback, NULL)", "atexit(exit_callback)")
    exe = os.path.join(src, "nuzzle")
    files = [f for f in sources(src, ["src/*.c"]) if not os.path.basename(f).startswith("ut_")]
    # Its shapes and colours ship in cfg/; the build points it at the game's home instead of /usr.
    cc(abi, files, exe, f'-std=gnu99 -I{src}/inc -DPREFIX=\\".\\"', f"-L{abi.sysroot}/lib -lmenuw {abi.curses_libs}", cwd=src)
    stage(gid, src, "cfg", "share/games/nuzzle")
    return exe


SBRK_ARENA = """#include <errno.h>
#include <stddef.h>

static char arena[4 << 20];
static size_t used;

char *tuikio_sbrk(int n)
{
    if (n < 0 || (size_t)n > sizeof arena - used) {
        errno = ENOMEM;
        return (char *)-1;
    }
    char *p = arena + used;
    used += (size_t)n;
    return p;
}
"""


def build_wander(abi, gid, src):
    for f in glob.glob(os.path.join(src, "*.c")):
        text = open(f, encoding="latin-1").read()
        if '"/tmp/' in text:
            replace_in(f, *TMP, count=0)
    # Its strings live in one block grown with sbrk(), which musl does not support (it fails
    # with ENOMEM). A static arena hands out the same contiguous, ever-growing block.
    with open(os.path.join(src, "tuikio_sbrk.c"), "w") as fh:
        fh.write(SBRK_ARENA)
    exe = os.path.join(src, "wander")
    # wseek() and wclose() are used before their K&R definitions; both return int.
    cc(abi, [f for f in sources(src, ["*.c"])], exe,
       f"{LEGACY} -Wno-error=implicit-function-declaration -include signal.h -Dsbrk=tuikio_sbrk",
       abi.curses_libs, cwd=src)
    for f in sorted(os.listdir(src)):
        if f.endswith((".wrld", ".misc", ".nr")):
            install_asset(f"games/{gid}/{f}", os.path.join(src, f))
    return exe


def build_clines(abi, gid, src):
    replace_in(os.path.join(src, "src", "main.c"), '#define CLINES_DATA_DIR "/var/tmp"', '#define CLINES_DATA_DIR "."')
    autotools(abi, src, "")
    return os.path.join(src, "clines")


def build_autotools(abi, gid, src):
    if not os.path.exists(os.path.join(src, "configure")):
        run("autoreconf -fi", src)
    autotools(abi, src, "")
    found = [p for p in glob.glob(os.path.join(src, "**", gid.split("-")[0]), recursive=True) if os.access(p, os.X_OK) and os.path.isfile(p)]
    if not found:
        raise BuildError(f"{gid}: executable not found after make")
    return found[0]


def build_ularn(abi, gid, src):
    for f in glob.glob(os.path.join(src, "src", "*.c")):
        if '"/tmp/' in open(f, encoding="latin-1").read():
            replace_in(f, *TMP, count=0)
    # A winner gets letters by /bin/mail from a forked child; there is no mail here.
    replace_in(os.path.join(src, "src", "bill.c"), "\twait((int *)0);\n\tpid=getpid();\n",
               "\treturn 0; /* Tuikio: no local mail to deliver the letters to */\n")
    # Short pauses were a busy loop on ftime() (configure finds no setitimer here): sleep instead.
    replace_in(os.path.join(src, "src", "nap.c"), "#ifdef HAVE_SETITIMER\n#define bit",
               "#if 1 /* Tuikio */\nvoid ularn_napms(int time) { if (time > 0) usleep(time * 1000); }\n"
               "#elif defined(HAVE_SETITIMER)\n#define bit")
    # No shell escape: games get no shell (GameEnvironment), and this one falls back to /bin/sh.
    replace_in(os.path.join(src, "src", "tok.c"), "if (cc == '!')\t{", "if (0 && cc == '!')\t{")
    for mk in ("Makefile.in", "src/Makefile.in"):
        if os.path.exists(os.path.join(src, mk)):
            replace_in(os.path.join(src, mk), """-DLIBDIR='"@datadir@/Ularn"'""", """-DLIBDIR='"./Ularn"'""")
    # Its makefile sets LDFLAGS to just the libraries; keep the toolchain's link flags too.
    replace_in(os.path.join(src, "src", "Makefile.in"), "LDFLAGS= @LIBS@", "LDFLAGS= @LDFLAGS@ @LIBS@")
    if not os.path.exists(os.path.join(src, "configure")):
        run("autoreconf -fi", src)
    # Its terminal code takes the System V termio branch (compat/include/termio.h); the
    # alternative is 4.2BSD sgtty, which Linux never had.
    # 1980s code: it calls time(), wait() and friends without their headers.
    autotools(abi, src, "", cflags=f"{COMPAT} -DHAVE_TERMIO_H=1 -include time.h -include unistd.h -include sys/wait.h")
    for f in ("Uhelp", "Umaps", "Ufortune"):
        install_asset(f"games/{gid}/Ularn/{f}", os.path.join(src, "data", f))
    # (Uscore is left out: the game creates its scoreboard on first run.)
    return os.path.join(src, "src", "Ularn")


SPECIAL = {"ctris": build_ctris, "nuzzle": build_nuzzle, "wander": build_wander, "clines": build_clines, "autotools": build_autotools,
           "ularn": build_ularn}

for _g, _e in GAMES.items():
    game(_g, notice=(_e["lock"], _e["notice"]))(lambda abi, _g=_g: build(abi, _g))
