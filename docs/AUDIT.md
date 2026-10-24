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

## Round 2: playing past the first screen (2026-10-23)

The first round looked at each game's first screens. The second played into the game itself
(`native/tests/shots.json` scripts go past title screens and menus), read every game's key
handling in its source, ran every game at its own terminal size and at a larger one to find what
the smaller one cuts off (`fitcheck`), and scanned every binary for paths that don't exist on a
phone.

### What was wrong

| Problem | Games | Cause |
|---|---|---|
| Keys used in play had no button | Cavez of Phear (bomb, detonate), Freecell (columns a–h, undo), Canfield (stock, talon, hand, 1–4), Mille Bornes (cards 1–6), nudoku, Su-Do-Ku and Sudoku (digits), Pipes (go, fast, pause), Curse of War (build, speed, flags), Chroma (undo, redo), netToe (a1–c3), Backgammon (moves, double), Hack, Umoria and Ularn (their commands), Revenge (diagonals) | The screen never names them, so the first round's key chips couldn't offer them |
| Buttons named after keys, not what they do | the New BSD Games ("Enter", "Space") | One generic profile for 17 games |
| Buttons the game never uses | Miketron, Muncher, Fisher (Enter, Space), Bombardier (a pad), Pacman for Console, Snake, Worm | Shared profiles |
| Cut off | Curse of War (right of the map, prices, the key legend), Revenge (bottom of the board), Freesweep (a third of the board, behind scroll marks), Tornado (players and wind), Vitetris (bottom of the well) | Terminal sizes a row or a few columns short |
| Drawn plain | Color Lines | At 40×24 it draws one-character balls; at 37×37 it draws 3×3 balls |
| The pad didn't move | Umoria | It started with the original keyset (numbers), the pad sends h/j/k/l |
| Help missing | Umoria (help, splash, tombstone) | Its texts were looked up in /usr/lib/games/moria |
| Quit on "instructions? y" | Cribbage | Its pager is started with /bin/sh, which Android 8 and 9 lack |
| Paged text needs a shell | Go Fish, Hunt the Wumpus, Quiz | The same: /bin/sh -c $PAGER |
| Pager prompt broke | every 48-column game | The prompt was wider than the screen and wrapped |
| Questions with no answer chips | Backgammon ("Do you want the rules of the game?", "Enter 'r' to play red, 'w' to play white..."), Cribbage ("Long (to 121) or Short (to 61)?"), Gomoku ("black or white?"), Hack ("Long or short help?"), Phantasia ("[Q = Quit]", "1:Magic User 2:Fighter", "[123456]"), Backgammon Tutor ("-->") | No (y/n)-style marker to read |
| Menu chips did nothing | netToe and other games that read whole lines | The chip sent "1" without Enter |
| No keyboard where typing is the game | 21 text games (adventures, Boggle, Hangman, Typespeed, GNU Typist, ...) | The keyboard had to be opened by hand |
| Unused binaries in the APK | 7 games from parked work | They weren't in the catalog, but were packaged |

### What changed

- **The game's own keys** (manifest `keys`): a labelled button for every key a game uses that
  its profile has no button for ("Bomb", "Undo", "1"–"9", "a1"–"c3"...). They stay in the
  strip under the game whenever the game isn't asking a question; the keys its screen names
  are added after them.
- **Buttons named for the game** (manifest `control_labels`, `control_hide`): Mines' Enter
  reads "Reveal" and its Space "Flag"; buttons a game never uses are left out.
- **Questions**: "Do you want ...?", "Would you like ...?" give Yes and No; "A or B?" gives A and
  B; quoted keys ("'w' to play white"), "[Q = Quit]", numbered menus on one line ("1:Magic User")
  and digit choices ("[123456]") become chips. In games that read whole lines, a menu chip sends
  Enter after its key.
- **Typing games start with the keyboard up** (manifest `"input": "typing"`), with Esc, Tab,
  arrows and F-keys above it.
- **Terminal sizes**: Curse of War 82×26 on a 19×17 map, Revenge 80×26, Tornado 80×25, Vitetris
  48×25, Freesweep a 12×15 board, Color Lines 37×37.
- **Umoria** plays with the roguelike keyset and finds its help and screens.
- **Pagers without a shell**: Cribbage, Go Fish, Hunt the Wumpus and Quiz run the app's pager
  directly, and show the text as it is if there is none. The pager's prompt fits the screen.
- **Bombardier** has its own controls: Bomb, Pause, Quit.
- `tools/release/check_apk.py` fails an APK that carries a game binary no catalog game uses,
  and the catalog validator checks that `control_labels`/`control_hide` name real actions and
  that no key repeats a button.

### Still limited

- Termrex needs 140 columns, and Morpion Solitaire 80 (its board doesn't shrink): both are drawn
  small.
- Empire, Air Traffic Controller, Sail and Star Trek are command-line games: they're played by
  typing, with the keyboard up.
- Everything here was checked in a phone-sized emulation (Robolectric, 411×914 dp) and on the
  build machine, not on a real phone.

## Round 3: card pictures, sounds and nine Rust games (2026-10-24)

- **Card pictures from the games themselves.** Every card on Discover, Library and a game's
  page showed the same `>_` sketch. Now it shows a piece of the game's real screen, in the
  game's colours: the board, the map, the cards. `tools/catalog/capture_art.py` takes it from
  the screenshots RenderShotsTest leaves (`<id>-<tag>.screen.json`, every cell's character and
  colours), picking the liveliest part (box and block drawing, colour, a board rather than prose)
  or, where that picks a help column or a title box, a part named in its `PIN` table. It's stored
  in the manifest as `art` and drawn with the theme's colours, using the game screen's rule for
  ink too close to its background. Kio: Lantern Tide keeps its drawn picture. Text adventures show
  their opening text, which is what they are.
- **Screenshots past the menus.** For 31 more games `native/tests/shots.json` now plays into the
  game before the shot (Umoria's town, Cribbage's dealt hand, Star Trek's sector scan, Treasure
  Mines' board, rhex's hex map, a few pieces dropped in each falling-block game), so their
  pictures and audit shots show play, not a question.
- **Game sounds.** Games that beep (BEL: a wrong move, a hit, an alarm) beep on the phone: a
  short, soft tone made in the app, with a light tick when haptics are on. It's silent when the
  phone is on silent or vibrate, and You > Game sounds turns it off. Beeps closer together than
  120 ms are dropped. A scan of the binaries finds 12 games that ring: Air Traffic Controller,
  Backgammon, Backgammon Tutor, Canfield, Cribbage, Freesweep, Mille Bornes, Moon Buggy, Robots,
  Star Traders, Sudoku and Umoria.
- **Nine Rust games** (Balatro TUI, Blackguard, Inertia, minesweep, 4D Minesweeper, rhex,
  Sudoku (rs), Tetro TUI, ttyper), built as static PIEs with cargo through the same Zig toolchain
  and audited the same way: their own controls, terminal sizes that show everything (Balatro TUI
  112×40, minesweep 50×38, Sudoku (rs) 56×41), the Rust standard library's licence shipped with
  them.

## Every game

Text size and buttons are from the round 2 and 3 shots: past the title screen where a script gets there.

| Game | Orientation | Terminal | Controls | Text (dp) | Buttons under the game (audited screen) | Fixed (rounds 1 to 3) |
|---|---|---|---|---|---|---|
| 2048 (cli) | portrait | 80×24 | arrows4 | 30 | - | Every first game began with "Failed to parse highscore file". Fixed. |
| 2048 | portrait | 40×20 | grid4 | 30 | - |  |
| Colossal Cave Adventure | portrait | 48×32 | text | 19 | - | Portrait, 48 columns, word wrap (was 80 columns: tiny upright). Keyboard up at the start. |
| Arithmetic | any | 80×24 | text | 30 / 30 | 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, Minus | Digit buttons and minus. |
| ASCII-Chess | portrait | 72×40 | text | 13 | - | Keyboard up at the start. |
| ASCII Invaders | landscape | 80×24 | shooter | 16 | - |  |
| ASCII Pacman | landscape | 80×24 | xckm | 15 | - |  |
| ASCII Snake | portrait | 40×26 | arrows4 | 25 | - |  |
| ASCII-Tetris | portrait | 80×24 | qsd-blocks | 30 | - |  |
| Air Traffic Controller | landscape | 80×24 | text | 14 | - | Keyboard up at the start. |
| Backgammon | landscape | 80×24 | text | 18 | 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, Dash, Comma, Double, Save, Quit | Landscape: the board needs 80 columns. Digits, dash and comma for moves; Double, Save, Quit; its questions as chips. |
| Balatro TUI | landscape | 112×40 | balatro | 10 | - | New (Rust). Runs at 112×40: at 80×24 its cards' values are cut off. Play and Discard buttons. |
| Battleship | landscape | 80×24 | battleship-nb | 16 | Quit | Quit button; unused buttons gone. |
| Battlestar | portrait | 48×32 | text | 18 | - | Portrait, 48 columns, word wrap. Keyboard up at the start. |
| Blackguard | landscape | 80×24 | scoundrel | 16 | - | New (Rust). Its own buttons: Use, Fight, Bare-handed, Avoid. |
| Boggle | landscape | 80×24 | text | 30 | ? Help | Keyboard up at the start. |
| Bombardier | landscape | 80×25 | bomber | 16 | - | Its own controls (Bomb, Pause, Quit) instead of an unused pad. |
| Canfield | landscape | 80×24 | text | 17 | Stock, Talon, Hand, 1, 2, 3, 4, Foundation, c Toggle card, b Present, q Quit | Its command legend as chips. Stock, Talon, Hand, 1-4 and Foundation buttons. |
| Cavez of Phear | landscape | 80×25 | arrows | 13 | Bomb, Detonate, Give up, Quit | "Continue" no longer covers the title. Bomb, Detonate, Give up and Quit buttons. |
| Checkers | landscape | 80×24 | nbsd | 26 | Quit | Enter named for what it does; Quit button. |
| Chroma | landscape | 80×24 | arrows | 14 | Undo, Redo, Fast, Pause | Menu readable; H/E/D/Q as chips. Undo, Redo, Fast, Pause; Enter reads Swap. |
| Color Lines | portrait | 37×37 | space-select | 16 | Quit | 40×24: the board fills the screen. 37x37: balls drawn 3x3 instead of one character. |
| Cribbage | landscape | 80×24 | text | 15 | - | Quit when asked for instructions (no shell for its pager); keyboard up; Long/Short chips. |
| csol | landscape | 80×24 | csol | 20 | Undo, Waste, Redeal, Help | Undo, Waste, Redeal and Help buttons. |
| CTRIS | landscape | 80×24 | blocks | 17 | - | Landscape (it refuses to start below 80×24). |
| Curse of War | landscape | 82×26 | arrows | 13 | Build, Faster, Slower, Pause, Clear flags, Halve flags, Quit, X Remove all, C Remove, P Pause | 82x26 on a 19x17 map: the map's right side, prices and key legend were cut off. Build, speed and flag buttons. |
| Dots and Boxes | landscape | 40×18 | vikeys8 | 26 | Quit | 8×6 board (was 3×3) in a 40×18 terminal, score beside it. Space reads Draw; Quit button. |
| Darrt | landscape | 80×24 | text | 12 | Quit | Quit button; unused buttons gone. |
| Empire | landscape | 80×24 | text | 14 | - | Keyboard up at the start. |
| Fifteen | landscape | 80×24 | nbsd | 30 | Quit | Enter named for what it does; Quit button. |
| Fisher | landscape | 80×24 | nbsd | 14 | Quit | Quit button; unused buttons gone. |
| Freecell | landscape | 80×24 | text | 24 | a, b, c, d, e, f, g, h, Undo, Quit, Help | Column buttons a-h, Undo, Quit, Help; Space and Enter read Free cell and Home. |
| Freesweep | portrait | 60×24 | mines | 15 | ? Help | A 12x15 board: a third of the 15x15 one was off screen. |
| Go Fish | portrait | 48×32 | text | 22 | - | Portrait, 48 columns, word wrap. Instructions paged without a shell; keyboard up. |
| Gomoku | landscape | 80×24 | text | 12 | Black, White | Keyboard up at the start. |
| Greed | landscape | 80×24 | greed | 13 | ? Help |  |
| GNU Typist | landscape | 80×24 | arrows | 12 | Continue | Pad for its menus; keyboard up. |
| Hack | landscape | 80×24 | roguelike | 14 | Wield, Wear, Take off, Eat, Quaff, Read, Zap, Throw, Drop, Up, Rest, Look, Save, Help | 14 command buttons (wield, wear, eat, quaff, read...); "Long or short help?" chips. |
| Hamurabi | portrait | 48×32 | text | 18 | 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 | Portrait, 48 columns, word wrap. Digit buttons. |
| Hangman | landscape | 80×24 | text | 19 | - | Keyboard up at the start. |
| Inertia | landscape | 80×24 | arrows | 14 | Box, Sphere, Star, Gravity, Wind, Clear, Zoom in, Zoom out, Help, d Place | New (Rust). Pause and Quit named; spawn keys (Box, Sphere, Star, Gravity, Wind...) as buttons. |
| Jewels | portrait | 40×24 | jkl-blocks | 24 | - | 40×24 instead of 80×24. |
| Kio: Lantern Tide | portrait | 34×21 | lantern | 26 | - |  |
| Memory Blocks | landscape | 80×24 | nbsd | 26 | Quit | Enter named for what it does; Quit button. |
| Miketron | landscape | 80×24 | nbsd | 14 | Quit | Quit button; unused buttons gone. |
| Mille Bornes | landscape | 80×24 | text | 18 | Pick, Use, Discard, 1, 2, 3, 4, 5, 6, q Quit, o Order hand, s Save, w Toggle, r Reprint | Its command legend as chips. Pick, Use, Discard and card 1-6 buttons. |
| Mines | landscape | 80×24 | nbsd | 28 | Quit | Enter reads Reveal, Space reads Flag. |
| minesweep | portrait | 50×38 | mines | 17 | - | New (Rust). 50×38 so the whole board shows; New and Help, which it doesn't use, left out. |
| 4D Minesweeper | landscape | 80×24 | mines4d | 15 | - | New (Rust). Its own pad with the Z and W axes, Uncover, Flag and Find. |
| Monopoly | portrait | 48×32 | text | 26 | - | Portrait, 48 columns, word wrap. Keyboard up at the start. |
| Moon Buggy | landscape | 80×24 | buggy | 18 | - |  |
| Umoria | landscape | 80×24 | roguelike | 14 | Wear, Take off, Eat, Quaff, Read, Drop, Cast, Pray, Rest, Up, Look, Map, Open, Close, Character, Help | m) Male / f) Female as chips. The pad didn't move (original keyset): roguelike keys now; help and screens found; 16 command buttons. |
| Morpion Solitaire | portrait | 80×46 | morpion | 12 | - |  |
| msnake | landscape | 80×24 | snake | 14 | - |  |
| Muncher | landscape | 80×24 | nbsd | 14 | Quit | Quit button; unused buttons gone. |
| Netris | landscape | 80×24 | jkl-blocks | 15 | - | Landscape: its title wraps over the board below 77 columns. |
| netToe | landscape | 80×24 | text | 20 | a1, a2, a3, b1, b2, b3, c1, c2, c3 | Menu entries as chips. Screens were cleared bright red (terminfo colour 9); a1-c3 move buttons; menu chips send Enter. |
| nInvaders | landscape | 80×24 | invaders | 16 | - |  |
| nSnake | landscape | 80×24 | snake | 14 | - |  |
| nudoku | landscape | 80×24 | arrows | 18 | 1, 2, 3, 4, 5, 6, 7, 8, 9, Delete, Check, Hint, New, Quit, m Toggle marks, r Redraw, S Solve puzzle | Its commands as chips (moves stay on the pad). Digit buttons, Delete, Check, Hint, New, Quit. |
| Nuzzle | landscape | 80×24 | arrows | 14 | Next, Quit | Menu readable (was black on navy). Next (Tab) and Quit; Enter reads Drop. |
| Pacman for Console | portrait | 32×32 | arrows | 19 | Pause, Quit | Did not start: looked for its levels in /usr/share. Fixed. Pause and Quit; unused buttons gone. |
| Petris | portrait | 50×24 | petris | 21 | - | 50×24 instead of 80×24: the well is much larger. |
| Phantasia | landscape | 80×24 | text | 13 | Yes, No, Q Quit | Class menu, [123456] and [Q = Quit] as chips; keyboard up. |
| Pipes | landscape | 80×24 | nbsd | 21 | Go, Fast, Pause, Quit | Go, Fast, Pause, Quit; Enter reads Place. |
| Quiz | portrait | 48×32 | text | 19 | - | Portrait, 48 columns, word wrap. Subject list paged without a shell; keyboard up. |
| Rabbithole | landscape | 80×24 | nbsd | 15 | Quit | Quit button; unused buttons gone. |
| Red Square | landscape | 80×24 | nbsd | 15 | Quit | Quit button; unused buttons gone. |
| Revenge | landscape | 80×26 | vikeys8 | 13 | Pause, Quit | 80x26: the board's bottom was cut off; 8-way pad for its diagonals. |
| Reversi | landscape | 80×24 | nbsd | 26 | Quit | Enter named for what it does; Quit button. |
| rhex | landscape | 80×24 | rhex | 14 | - | New (Rust). Turn, step and wait buttons for its hex grid. |
| robotfindskitten | landscape | 80×24 | kitten | 13 | - |  |
| Robots | landscape | 80×24 | robots | 13 | q Quit |  |
| Sail | landscape | 80×24 | text | 14 | - | Keyboard up at the start. |
| Scissor | landscape | 80×24 | nbsd | 14 | Pause, Quit | Quit button; unused buttons gone. |
| Square Jump | landscape | 80×24 | nbsd | 14 | Quit | Enter named for what it does; Quit button. |
| Snake | landscape | 80×24 | arrows | 14 | Warp, Quit | Warp and Quit buttons; unused buttons gone. |
| Snake Duel | landscape | 80×24 | nbsd | 14 | Yes, No | Quit button; unused buttons gone. |
| SOS | landscape | 80×24 | sos-nb | 30 | Quit | Quit button; unused buttons gone. |
| Sudoku | landscape | 80×24 | nbsd | 21 | 1, 2, 3, 4, 5, 6, 7, 8, 9, New, Restart, Quit | Digit buttons, New, Restart, Quit; Space reads Clear. |
| Sudoku (rs) | portrait | 56×41 | arrows | 13 | 1, 2, 3, 4, 5, 6, 7, 8, 9, Beginner, Easy, Hard, Give up, Quit | New (Rust). 56×41 shows its side panel; 1–9, Clear and the difficulty keys as buttons. |
| Su-Do-Ku | landscape | 80×24 | arrows | 14 | 1, 2, 3, 4, 5, 6, 7, 8, 9, Clear, Undo, Hint, New, Restart, Quit, c Clear board, d Redraw, f Fix squares, v Solve | Digit buttons, Clear, Undo, Hint, New, Restart, Quit. |
| Backgammon Tutor | landscape | 80×24 | text | 18 | Continue | Landscape: the board needs 80 columns. Digits, dash and comma for moves; Continue at its "-->" prompt. |
| Termrex | landscape | 140×28 | dino | 10 | - |  |
| Tetris (berk76) | landscape | 80×24 | berk-tetris | 14 | - |  |
| Tetris (BSD) | landscape | 80×24 | jkl-blocks | 15 | - | Landscape: its key line garbles the score below 72 columns. |
| Tetro TUI | portrait | 80×24 | tetro | 14 | ? See all | New (Rust). Its own controls (drop, turn, hold). |
| Threes | portrait | 80×24 | wasd4 | 18 | - |  |
| TINT | portrait | 80×24 | blocks | 11 | - |  |
| TinyCols | portrait | 38×16 | columns | 24 | - |  |
| TinyTetris | portrait | 22×22 | wasd-blocks | 28 | - |  |
| Tornado | landscape | 80×25 | tornado | 13 | l Lightning, c Current, q Quit, o Highscores | Its weather legend as chips. 80x25: the players' line was cut off. |
| Star Traders | landscape | 80×24 | text | 12 | 1 Display, 3 Save, 2 Declare, C Quit | Dialog text readable (was white on light blue). Keyboard up at the start. |
| Star Trek | any | 80×24 | text | 18 / 14 | - | Keyboard up at the start. |
| TROG | landscape | 80×24 | qweasdzxc | 14 | - | "Continue" no longer covers the title. |
| Treasure Mines | landscape | 80×24 | nbsd | 17 | Quit | Enter named for what it does; Quit button. |
| tty-solitaire | landscape | 57×28 | space-select | 12 | More, Fewer, Quit | More, Fewer and Quit buttons. |
| ttyper | landscape | 80×24 | text | 14 | - | New (Rust). Starts with the keyboard up. |
| Tug of War | landscape | 80×24 | text | 14 | 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, Quit | Digit buttons. |
| Typespeed | landscape | 80×24 | text | 14 | - | Keyboard up at the start. |
| Ularn | landscape | 80×24 | roguelike | 14 | Continue | "Continue" no longer covers the text. 10 command buttons. |
| UNIX Aliens | landscape | 80×24 | aliens | 16 | - | Game modes 1-4 as chips. |
| Vitetris | portrait | 48×25 | blocks | 20 | - | 48x25: the well's bottom was cut off. |
| Wander | portrait | 48×32 | text | 18 | - | Portrait, 48 columns, word wrap. Keyboard up at the start. |
| Worm | landscape | 80×24 | arrows | 14 | - | Unused buttons gone. |
| Hunt the Wumpus | portrait | 48×32 | text | 18 | m, s | Portrait, 48 columns, word wrap; "(m-s)" answers as chips. Instructions paged without a shell; digits for rooms; keyboard up. |
| Xonitix | landscape | 80×24 | xonitix | 21 | - |  |
