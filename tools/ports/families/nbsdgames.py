"""New BSD Games by abakh (native/recipes/nbsdgames.py)."""

DEFAULTS = dict(
    license="CC0-1.0 OR Apache-2.0",
    authors=["abakh"],
    source_url="https://github.com/abakh/nbsdgames",
    version="git d9494d5 (2026-10-20)",
    archive="nbsdgames-d9494d50.tar",
    recipe="native/recipes/nbsdgames.py",
    license_file="nbsdgames.txt",
    terminal=((40, 16), (80, 24)),
    orientation="landscape",
    controls="nbsd.v1",
    host_tested="2026-10-23",
    discovery="nbsd-games-{name}",
    discovery_url="https://github.com/abakh/nbsdgames",
    discovery_notes="Ported to Tuikio from New BSD Games.",
)


def g(name, title, summary, description, category, tags, test, **kw):
    d = dict(id=f"{name}-nb", exe=f"game_{name}_nb", title=title, summary=summary, description=description,
             category=category, tags=tags, test_notes=f"Host test: {test}.", discovery=f"nbsd-{name}",
             saves=([f"{name}_scores"], "High scores") if kw.pop("scores", False) else ([], "No saves."))
    d.update(kw)
    return d


GAMES = [
    g("battleship", "Battleship", "Hide your fleet, then hunt the enemy's ships square by square.",
      "Place your ships (Rotate turns them), then take turns firing at the computer's grid, or play a "
      "friend on the same phone.", "strategy", ["naval", "two player", "classic"], "game type menu",
      controls="battleship-nb.v1", players="1-2"),
    g("checkers", "Checkers", "Draughts against the computer or a friend, with forced captures and kings.",
      "Choose who plays each side (H for human, c for computer), then select a piece and its destination "
      "with the pad and Enter, or tap the board.", "strategy", ["board", "two player", "classic"],
      "player setup prompt", players="1-2"),
    g("darrt", "Darrt", "An aiming game played with the whole keyboard.",
      "Letters sweep across the target: type the right one at the right moment to land a dart near the "
      "centre. Open the keyboard to play.", "arcade", ["typing", "aiming"], "target and score",
      controls="text.v1", scores=True),
    g("fifteen", "Fifteen", "The sliding-tile puzzle: put the numbers back in order.",
      "Move the cursor to a tile next to the gap and press Enter to slide it.", "puzzle",
      ["sliding", "classic"], "board drawn"),
    g("fisher", "Fisher", "Lower your hook and land the fish without losing all your hooks.",
      "Up and down move the hook. Catch fish for points; snagging the wrong thing costs a hook.",
      "arcade", ["fishing", "casual"], "hook, fish and score", scores=True),
    g("jewels", "Jewels", "Falling jewels: line up three of a kind to clear them.",
      "Slide and rotate the falling column of jewels; matching three or more in a line clears them.",
      "puzzle", ["blocks", "match three"], "playfield and key line", controls="jkl-blocks.v1",
      orientation="portrait", terminal=((40, 24), (40, 24)), scores=True),
    g("memoblocks", "Memory Blocks", "Turn over pairs of blocks and remember where everything is.",
      "Reveal two blocks at a time with Enter; matching pairs stay open.", "puzzle",
      ["memory", "cards"], "board drawn"),
    g("miketron", "Miketron", "Steer a growing trail around the arena, grabbing prizes and dodging the S's.",
      "Change direction with the pad. Your trail is a wall; so is everything else.", "arcade",
      ["snake", "tron"], "arena and score", scores=True),
    g("mines", "Mines", "Minesweeper: clear the field without stepping on a mine.",
      "Enter examines the square under the cursor, Space plants or removes a flag. Numbers count the "
      "mines around a square.", "puzzle", ["minesweeper", "logic"], "minefield and counters"),
    g("muncher", "Muncher", "Munch your way around the field while it fills up behind you.",
      "Change direction with the pad and eat everything you can.", "arcade", ["snake", "casual"],
      "field and score", scores=True),
    g("pipes", "Pipes", "Lay pipe for the oncoming flow before it spills.",
      "Place the next pipe piece under the cursor with Enter; g starts the flow early, p pauses.",
      "puzzle", ["pipes", "timed"], "board, timer and score", scores=True),
    g("rabbithole", "Rabbithole", "Guide a rabbit through a maze to the carrots before time runs out.",
      "Move with the pad; collect every carrot before the timer ends.", "puzzle", ["maze", "timed"],
      "maze, timer and carrots"),
    g("redsquare", "Red Square", "Conway's Game of Life, made playable.",
      "Move your square through the living cells, catch the X and outnumber the red ones.", "puzzle",
      ["life", "cellular automaton"], "grid and score", scores=True),
    g("revenge", "Revenge", "Push blocks to trap the creatures that hunt you.",
      "Move with the pad; Enter slides the block in front of you.", "arcade", ["blocks", "action"],
      "field and score", scores=True),
    g("reversi", "Reversi", "Othello against the computer or a friend.",
      "Choose who plays each side (H for human, c for computer), then place pieces with the pad and Enter "
      "or by tapping. Outflank to flip.", "strategy", ["board", "two player", "classic"],
      "player setup prompt", players="1-2"),
    g("scissor", "Scissor", "Rock, paper, scissors turned into an arcade game of its own.",
      "Chase what you beat and run from what beats you.", "arcade", ["action", "original"],
      "field and score", scores=True),
    g("sjump", "Square Jump", "Time your jumps from square to square and keep the combo going.",
      "Press Enter to jump; land on the next square to build your combo.", "arcade", ["timing", "casual"],
      "squares, score and combo", scores=True),
    g("snakeduel", "Snake Duel", "Two snakes, one arena: make the computer's snake crash first.",
      "Change direction with the pad. Whoever runs into a wall or a tail loses the round.", "arcade",
      ["snake", "versus"], "arena and win counters"),
    g("sos", "SOS", "Take turns writing S or O; spell S-O-S to score.",
      "Choose who plays each side (H for human, c for computer). Every SOS you complete scores a point "
      "and gives you another turn.", "strategy", ["board", "two player"], "player setup prompt",
      controls="sos-nb.v1", players="1-2"),
    g("sudoku", "Sudoku", "Fill the grid so every row, column and box holds each number once.",
      "Move with the pad and type numbers with the keyboard; Space clears a square, n starts a new board.",
      "puzzle", ["sudoku", "logic", "numbers"], "grid and progress counters"),
    g("trsr", "Treasure Mines", "Minesweeper for two: race the computer or a friend to find the mines.",
      "Choose who plays each side (H for human, c for computer), then take turns examining squares.",
      "puzzle", ["minesweeper", "two player"], "player setup prompt", players="1-2"),
    g("tugow", "Tug of War", "A typing duel: win the rope by typing faster than your rival.",
      "Type the numbers shown (or home-row letters after pressing h) to pull the rope your way. Open the "
      "keyboard to play.", "words", ["typing", "speed"], "title and rope", controls="text.v1", scores=True),
]
