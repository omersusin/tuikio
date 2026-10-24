"""Terminal games written in Rust, pinned by commit (native/recipes/rust.py)."""

DEFAULTS = dict(
    recipe="native/recipes/rust.py",
    terminal=((80, 24), (80, 24)),
    orientation="landscape",
    host_tested="2026-10-23",
    discovery_notes="Ported to Tuikio from the upstream repository, pinned by commit; built with cargo for Android's "
                    "musl targets.",
)


def gh(repo, commit, name=None):
    """Upstream fields for a game pinned at <commit>; the archive is <name>-<commit>.tar (git_lock.py)."""
    name = name or repo.split("/")[1].lower()
    return dict(source_url=f"https://github.com/{repo}", version=f"git {commit}", archive=f"{name}-{commit}.tar")


GAMES = [
    dict(id="ttyper", title="ttyper", exe="game_ttyper", controls="text.v1", input="typing",
         summary="A typing test: type the words as they scroll by and see your speed and accuracy.",
         description="Type the prompt with the keyboard. At the end you get words per minute, accuracy and your "
                     "slowest keys; r starts again.",
         category="words", tags=["typing", "practice"], authors=["Max Niederman"], license="MIT",
         **gh("max-niederman/ttyper", "e218bad9"), test_notes="Host test: prompt shown, typing counts."),
    dict(id="sudoku-rs", title="Sudoku (rs)", exe="game_sudoku_rs", controls="arrows.v1", orientation="portrait",
         labels={"space": "Clear"}, hide=["select", "back"],
         keys=["#1-9", "Beginner=z", "Easy=x", "Hard=c", "Give up=g", "Quit=q"],
         summary="Sudoku with big, clear cells and three difficulties.",
         description="Move with the pad and tap a digit under the board; Clear empties a square. Beginner, Easy and "
                     "Hard start a new puzzle.",
         category="puzzle", tags=["sudoku", "logic", "numbers"], authors=["Mitchel Paulin"], license="GPL-3.0-only",
         **gh("MitchelPaulin/sudoku-rs", "c8b6572a"), terminal=((56, 41), (56, 41)),
         test_notes="Host test: puzzle with its info panel at 56x41 (at 29 rows only the board shows)."),
    # Its 9x9 grid of 5x3 tiles needs 50x38; at 80x24 it was squeezed into one row.
    dict(id="minesweep-rs", title="minesweep", exe="game_minesweep_rs", controls="mines.v1", orientation="portrait",
         terminal=((50, 38), (50, 38)), hide=["new", "help"],
         summary="Minesweeper with a clean grid: clear the field without touching a mine.",
         description="Move with the pad, Reveal opens a tile and Flag marks a mine.",
         category="puzzle", tags=["minesweeper", "logic"], authors=["Phillip Cloud"], license="Apache-2.0",
         **gh("cpcloud/minesweep-rs", "6ca1c255"), test_notes="Host test: field and key help."),
    dict(id="tetro-tui", title="Tetro TUI", exe="game_tetro_tui", controls="tetro.v1", orientation="portrait",
         summary="A modern falling-tetromino game: hold, previews, rotation systems, replays and stats.",
         description="Pick New Game from the menu. Turn rotates, Hold stores a piece, Up drops it hard. Menu pauses "
                     "and has settings for handling and graphics.",
         category="arcade", tags=["blocks", "tetris", "modern"], authors=["Lucas W. (Strophox)"], license="MIT",
         **gh("Strophox/tetro-tui", "4b6c7cb3"), test_notes="Host test: title menu."),
    dict(id="rhex", title="rhex", exe="game_rhex", controls="rhex.v1",
         summary="An early, atmospheric roguelike on a hexagonal map, where what you see depends on where you face.",
         description="Press any key, choose a race (a for human), then explore. Turn with left and right, walk with "
                     "up. ? shows the full help.",
         category="roguelike", tags=["roguelike", "hex", "exploration"], authors=["Dawid Ciężarkiewicz"], license="MPL-2.0",
         **gh("dpc/rhex", "dd1b6b66"), test_notes="Host test: introduction screen."),
    dict(id="minesweeper-4d", title="4D Minesweeper", exe="game_minesweeper_4d", controls="mines4d.v1",
         args=["-c", "config.toml"],
         summary="Minesweeper in four dimensions: every cell has 80 neighbours.",
         description="The board is a grid of grids: x and y move inside a grid, Z and W move between grids. Delta "
                     "mode shows how many unflagged mines remain around a cell.",
         category="puzzle", tags=["minesweeper", "4d", "logic"], authors=["itabesamesa"], license="MIT",
         **gh("itabesamesa/minesweeper_4d_rs", "c9480eec", "minesweeper-4d-rs"),
         test_notes="Host test: game info and board."),
    # Below 112x40 its hands, discards, money and ante boxes are drawn empty.
    dict(id="balatro-tui", title="Balatro TUI", exe="game_balatro_tui", controls="balatro.v1", discovery="balatrotui",
         terminal=((112, 40), (112, 40)),
         summary="A Balatro-style poker roguelike: play hands to beat each blind's score.",
         description="Pick cards from your hand with the pad, then Play them as a poker hand or Discard them for new "
                     "ones. Beat the blind's target score to move on.",
         category="cards", tags=["poker", "roguelike", "deckbuilder"], authors=["Utkarsh Bhardwaj (Passeriform)"],
         license="GPL-3.0-only", **gh("Passeriform/BalatroTUI", "01bbb9ac"), test_notes="Host test: round drawn."),
    dict(id="inertia", title="Inertia", exe="game_inertia", controls="arrows.v1",
         labels={"space": "Pause", "back": "Quit"},
         keys=["Box=b", "Sphere=s", "Star=n", "Gravity=g", "Wind=w", "Clear=X", "Zoom in=+", "Zoom out=-", "Help=?"],
         summary="A physics sandbox: rigid bodies, gravity, cloth, fluid and heat, drawn in the terminal.",
         description="Pick a demo with the pad and Select (or 1 to 6), or start empty and spawn boxes, spheres and "
                     "stars with the buttons under it; tap to place. ? lists every key.",
         category="simulation", tags=["physics", "sandbox", "toy"], authors=["Kairav Mittal"], license="MIT OR Apache-2.0",
         **gh("aclfe/inertia", "80886289"), test_notes="Host test: scene menu."),
    dict(id="blackguard", title="Blackguard", exe="game_blackguard", controls="scoundrel.v1",
         summary="Scoundrel, the solo dungeon card game: fight, equip and heal your way through the deck.",
         description="Each room is four cards. Pick one with the pad and Use it: monsters are fought, weapons "
                     "equipped, potions drunk. Avoid skips a room, but never twice in a row.",
         category="cards", tags=["solitaire", "roguelike", "scoundrel"], authors=["Elnur Badalov"], license="MIT",
         **gh("ElnurBDa/blackguard", "294bfd37"), test_notes="Host test: first room dealt."),
]
