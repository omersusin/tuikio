# Game audit (2026-10-23)

Every game in the catalog was started on the real game screen and played through its first
screens, in the orientation it runs in, on a 411×914 dp phone (Robolectric with native graphics:
`./gradlew :app:test --tests '*RenderShotsTest*' -Pshots=primary`). Each screen was checked for two
things the owner reported:

1. **Keys the game asks for that the app has no button for.** "Press a key it shows, and there is
   no such button."
2. **Games that look half-finished.** Tiny, lopsided, cut off or unreadable.

## What was wrong

| Problem | Games | Cause |
|---|---|---|
| Didn't start | Pacman for Console | Looked for its levels in `/usr/share` (a header overrode the build flag) |
| Error on first start | 2048 (cli) | An empty new score file read as "Failed to parse highscore file" |
| Small board in a wide frame | Dots and Boxes, Color Lines, CTRIS, csol, 2048 (cli), … | The view zoomed to everything ever drawn, including title screens and blank cells painted black |
| Tiny text upright | Colossal Cave, Battlestar, Wumpus, Go Fish, Monopoly, Quiz, Wander, Hamurabi | 80-column prose in a portrait screen: 5 dp wide characters |
| Tiny falling-block games | Netris, Petris, BSD Tetris, Jewels | 80-column terminals for 20-column wells |
| Unreadable colours | Star Traders, Nuzzle, Chroma | White on the theme's light blue; black on navy |
| Chips covering the game | Cavez of Phear, Ularn, TROG, Snake Duel | Answer chips were laid over the game's text |
| Keys with no button | Chroma, netToe, Moria, Mille Bornes, nudoku, Tornado, Canfield, UNIX Aliens, Star Traders, Greed, Boggle, Freesweep, Wumpus … | Menus and key legends only reachable through the phone keyboard |
| Keys a phone keyboard doesn't have | Any game wanting Esc, Tab, Ctrl or F1–F12 (TROG quits with F12) | No way to send them |

## What changed

For every game:

- **Key chips.** The screen is read for menus ("1) Start", "(q) Quit", "m) Male"), legends
  ("q - quit", "s = Snow", "d: discard"), brackets ("<C> to continue", "[H] How to play") and "press
  E to …". Every key that no visible button sends becomes a chip with its key and label. The
  answers to questions ("[ny]", "(m-s)", "[1-9]", "--More--") stay chips as before.
- **Chips never cover the game.** They sit in a strip under it; the game moves up, and only
  shrinks if it must.
- **Esc, Tab, Ctrl, Alt, arrows, Home/End, PgUp/PgDn and F1–F12** come with the keyboard. The
  keyboard button now opens typing mode, and the controls step aside while you type.
- **Zoom follows the game.** The view fits what is on screen now. It grows at once but shrinks
  only after a layout holds for 1.5 s, so a wide title or help screen no longer leaves the game
  small. Blank cells painted black don't count.
- **Readable colours.** Text whose colour is too close to its background (contrast under 3:1) is
  drawn in the screen's light or dark ink instead.
- **Exact fit.** Text size is checked at the size itself, so games that fill the width no longer
  lose a sliver of their first and last columns.
- **Word wrap for prose.** Games that only print lines can opt in (`"wrap": "words"` in the
  manifest). A word that would break at the edge moves to the next line. Eight text games now run
  upright at 48 columns, readable without zooming.

Per game: see the last column below.

## Text size

Before, 11 games were drawn at 12 dp or less in the orientation they run in (a 5 dp wide
character on a phone). Now only Termrex is (it needs 140 columns). The median is 17 dp.

Remaining small games are full-screen 80×24 layouts that use the whole grid (Greed, Robots,
robotfindskitten, Hack, Cavez of Phear): in landscape, the side controls leave about 13–14 dp per
row for them, as a terminal app would.

## Every game

| Game | Orientation | Terminal | Controls | Text (dp) | Chips on the audited screen | Fixed in this audit |
|---|---|---|---|---|---|---|
| 2048 (cli) | portrait | 80×24 | arrows4 | 30 | - | Every first game began with "Failed to parse highscore file". Fixed. |
| 2048 | portrait | 40×20 | grid4 | 30 | - |  |
| Colossal Cave Adventure | portrait | 48×32 | text | 19 | - | Portrait, 48 columns, word wrap (was 80 columns: tiny upright). |
| Arithmetic | any | 80×24 | text | 30 / 30 | - |  |
| ASCII-Chess | portrait | 72×40 | text | 13 | - |  |
| ASCII Invaders | landscape | 80×24 | shooter | 16 | - |  |
| ASCII Pacman | landscape | 80×24 | xckm | 15 | - |  |
| ASCII Snake | portrait | 40×26 | arrows4 | 25 | - |  |
| ASCII-Tetris | portrait | 80×24 | qsd-blocks | 30 | - |  |
| Air Traffic Controller | landscape | 80×24 | text | 16 | - |  |
| Backgammon | landscape | 80×24 | text | 30 | - | Landscape: the board needs 80 columns. |
| Battleship | landscape | 80×24 | battleship-nb | 16 | - |  |
| Battlestar | portrait | 48×32 | text | 18 | - | Portrait, 48 columns, word wrap. |
| Boggle | landscape | 80×24 | text | 30 | ? Help |  |
| Bombardier | landscape | 80×25 | space-select | 14 | - |  |
| Canfield | landscape | 80×24 | text | 17 | c Toggle card, b Present, q Quit | Its command legend as chips. |
| Cavez of Phear | landscape | 80×25 | arrows | 13 | Continue | "Continue" no longer covers the title. |
| Checkers | landscape | 80×24 | nbsd | 30 | - |  |
| Chroma | landscape | 80×24 | arrows | 17 | H How, E Editor, D Display, Q Quit | Menu readable; H/E/D/Q as chips. |
| Color Lines | portrait | 40×24 | space-select | 30 | - | 40×24: the board fills the screen. |
| Cribbage | landscape | 80×24 | text | 30 | - |  |
| csol | landscape | 80×24 | csol | 20 | - |  |
| CTRIS | landscape | 80×24 | blocks | 17 | - | Landscape (it refuses to start below 80×24). |
| Curse of War | landscape | 80×24 | arrows | 14 | - |  |
| Dots and Boxes | landscape | 40×18 | vikeys8 | 30 | - | 8×6 board (was 3×3) in a 40×18 terminal, score beside it. |
| Darrt | landscape | 80×24 | text | 16 | - |  |
| Empire | landscape | 80×24 | text | 16 | - |  |
| Fifteen | landscape | 80×24 | nbsd | 30 | - |  |
| Fisher | landscape | 80×24 | nbsd | 14 | - |  |
| Freecell | landscape | 80×24 | text | 28 | - |  |
| Freesweep | portrait | 60×24 | mines | 15 | ? Help |  |
| Go Fish | portrait | 48×32 | text | 22 | - | Portrait, 48 columns, word wrap. |
| Gomoku | landscape | 80×24 | text | 16 | - |  |
| Greed | landscape | 80×24 | greed | 13 | ? Help |  |
| GNU Typist | landscape | 80×24 | text | 16 | - |  |
| Hack | landscape | 80×24 | roguelike | 14 | - |  |
| Hamurabi | portrait | 48×32 | text | 18 | - | Portrait, 48 columns, word wrap. |
| Hangman | landscape | 80×24 | text | 19 | - |  |
| Jewels | portrait | 40×24 | jkl-blocks | 24 | - | 40×24 instead of 80×24. |
| Kio: Lantern Tide | portrait | 34×21 | lantern | 26 | - |  |
| Memory Blocks | landscape | 80×24 | nbsd | 30 | - |  |
| Miketron | landscape | 80×24 | nbsd | 14 | - |  |
| Mille Bornes | landscape | 80×24 | text | 18 | p Pick, q Quit, u Use, o Order hand, d Discard, s Save | Its command legend as chips. |
| Mines | landscape | 80×24 | nbsd | 30 | - |  |
| Monopoly | portrait | 48×32 | text | 26 | - | Portrait, 48 columns, word wrap. |
| Moon Buggy | landscape | 80×24 | buggy | 16 | - |  |
| Umoria | landscape | 80×24 | roguelike | 16 | m Male, f Female | m) Male / f) Female as chips. |
| Morpion Solitaire | portrait | 80×46 | morpion | 12 | - |  |
| msnake | landscape | 80×24 | snake | 14 | - |  |
| Muncher | landscape | 80×24 | nbsd | 14 | - |  |
| Netris | landscape | 80×24 | jkl-blocks | 15 | - | Landscape: its title wraps over the board below 77 columns. |
| netToe | landscape | 80×24 | text | 23 | 1 Player vs, 2 Player vs, 3 Two players, 4 Info, q Quit | Menu entries as chips. |
| nInvaders | landscape | 80×24 | invaders | 16 | - |  |
| nSnake | landscape | 80×24 | snake | 14 | - |  |
| nudoku | landscape | 80×24 | arrows | 18 | c Check, H Give, m Toggle marks, N New puzzle, Q Quit, r Redraw | Its commands as chips (moves stay on the pad). |
| Nuzzle | landscape | 80×24 | arrows | 14 | - | Menu readable (was black on navy). |
| Pacman for Console | portrait | 32×32 | arrows | 20 | - | Did not start: looked for its levels in /usr/share. Fixed. |
| Petris | portrait | 50×24 | petris | 21 | - | 50×24 instead of 80×24: the well is much larger. |
| Phantasia | landscape | 80×24 | text | 17 | - |  |
| Pipes | landscape | 80×24 | nbsd | 25 | - |  |
| Quiz | portrait | 48×32 | text | 19 | - | Portrait, 48 columns, word wrap. |
| Rabbithole | landscape | 80×24 | nbsd | 15 | - |  |
| Red Square | landscape | 80×24 | nbsd | 18 | - |  |
| Revenge | landscape | 80×24 | nbsd | 16 | - |  |
| Reversi | landscape | 80×24 | nbsd | 30 | h, C |  |
| robotfindskitten | landscape | 80×24 | kitten | 13 | - |  |
| Robots | landscape | 80×24 | robots | 13 | q Quit |  |
| Sail | landscape | 80×24 | text | 16 | - |  |
| Scissor | landscape | 80×24 | nbsd | 14 | - |  |
| Square Jump | landscape | 80×24 | nbsd | 14 | - |  |
| Snake | landscape | 80×24 | arrows | 14 | - |  |
| Snake Duel | landscape | 80×24 | nbsd | 14 | Yes, No |  |
| SOS | landscape | 80×24 | sos-nb | 27 | h, C |  |
| Sudoku | landscape | 80×24 | nbsd | 25 | - |  |
| Su-Do-Ku | landscape | 80×24 | arrows | 14 | - |  |
| Backgammon Tutor | landscape | 80×24 | text | 22 | - | Landscape: the board needs 80 columns. |
| Termrex | landscape | 140×28 | dino | 10 | - |  |
| Tetris (berk76) | landscape | 80×24 | berk-tetris | 14 | - |  |
| Tetris (BSD) | landscape | 80×24 | jkl-blocks | 15 | - | Landscape: its key line garbles the score below 72 columns. |
| Threes | portrait | 80×24 | wasd4 | 18 | - |  |
| TINT | portrait | 80×24 | blocks | 27 | 1, 2, 3, 4, 5, 6, 7, 8, 9 |  |
| TinyCols | portrait | 38×16 | columns | 24 | - |  |
| TinyTetris | portrait | 22×22 | wasd-blocks | 28 | - |  |
| Tornado | landscape | 80×24 | tornado | 14 | l Lightning, c Current, q Quit, o Highscores | Its weather legend as chips. |
| Star Traders | landscape | 80×24 | text | 18 | C Continue | Dialog text readable (was white on light blue). |
| Star Trek | any | 80×24 | text | 24 / 30 | Continue |  |
| TROG | landscape | 80×24 | qweasdzxc | 14 | Continue | "Continue" no longer covers the title. |
| Treasure Mines | landscape | 80×24 | nbsd | 30 | h, C |  |
| tty-solitaire | landscape | 57×28 | space-select | 14 | - |  |
| Tug of War | landscape | 80×24 | text | 16 | - |  |
| Typespeed | landscape | 80×24 | text | 16 | - |  |
| Ularn | landscape | 80×24 | roguelike | 14 | Continue | "Continue" no longer covers the text. |
| UNIX Aliens | landscape | 80×24 | aliens | 16 | - | Game modes 1-4 as chips. |
| Vitetris | portrait | 48×24 | blocks | 20 | - |  |
| Wander | portrait | 48×32 | text | 18 | - | Portrait, 48 columns, word wrap. |
| Worm | landscape | 80×24 | arrows | 14 | - |  |
| Hunt the Wumpus | portrait | 48×32 | text | 18 | m, s | Portrait, 48 columns, word wrap; "(m-s)" answers as chips. |
| Xonitix | landscape | 80×24 | xonitix | 21 | - |  |
