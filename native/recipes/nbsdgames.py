"""New BSD Games by abakh: 22 small curses games, one C file each (CC0-1.0 OR Apache-2.0)."""
import os

from tk import cc, game, unpack

SRC = "nbsdgames-d9494d50.tar"
NAMES = ("battleship checkers darrt fifteen fisher jewels memoblocks miketron mines muncher pipes "
         "rabbithole redsquare revenge reversi scissor sjump snakeduel sos sudoku trsr tugow").split()


def build(abi, name):
    src = unpack(SRC, os.path.join(abi.work("nbsdgames"), name, "src"))
    exe = os.path.join(src, "src", name)
    # Score files go next to the game's saves (its home is the working directory).
    cc(abi, [os.path.join(src, "src", f"{name}.c")], exe,
       f'-std=gnu99 -Wno-unused-result -DSCORES_DIR=\\".\\" -I{os.path.join(src, "src")} {abi.curses_cflags}',
       f"{abi.curses_libs} -lm")
    return {f"game_{name}_nb": exe}


for _n in NAMES:
    game(f"{_n}-nb", notice=(SRC, ("LICENSE", "APACHE")), license_name="nbsdgames")(lambda abi, _n=_n: build(abi, _n))
