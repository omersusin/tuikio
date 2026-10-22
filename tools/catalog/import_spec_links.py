#!/usr/bin/env python3
"""One-off import of the project brief's discovery list into catalog/discovery.json.

Kept for provenance: it records exactly which links came from the brief (22 Oct 2026)
and how each was classified. After the import, catalog/discovery.json is the source of
truth and changes arrive as reviewed pull requests.

Rules applied here:
  * every entry starts as CANDIDATE unless Tuikio has a working port (then PORTABLE + game_id);
  * licenses are recorded only when verified from the source we built; otherwise "UNKNOWN";
  * nothing is guessed: unknown terminal sizes, controls and dependencies stay empty.
"""
import json
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
IMPORTED = "2026-10-22"

PORTED = {
    "ninvaders": ("ninvaders", "GPL-2.0-or-later"),
    "moon-buggy": ("moon-buggy", "GPL-2.0-or-later"),
    "2048-c": ("2048", "MIT"),
    "vitetris": ("vitetris", "BSD-2-Clause"),
    "nsnake": ("nsnake", "GPL-3.0-or-later"),
    "freesweep": ("freesweep", "GPL-2.0-or-later"),
    "robot-finds-kitten": ("robotfindskitten", "GPL-2.0-or-later"),
}

# (title, url, kind, category, notes)
SOURCES = [
    ("awesome-ttygames", "https://github.com/ligurio/awesome-ttygames", "discovery_list", "meta", "Primary curated list."),
    ("awesome-ttygames games.yaml", "https://github.com/ligurio/awesome-ttygames/blob/master/games.yaml", "discovery_list", "meta", "Machine-readable list; import periodically."),
    ("awesome-ttygames site", "https://ligurio.github.io/awesome-ttygames/", "discovery_list", "meta", ""),
    ("Awesome TUI (Games)", "https://github.com/alvinunreal/awesometui#games", "discovery_list", "meta", ""),
    ("awesometui.com games", "https://awesometui.com/games", "discovery_list", "meta", ""),
    ("cli-apps (Games)", "https://github.com/dnalor/cli-apps#games", "discovery_list", "meta", ""),
    ("Termux packages", "https://github.com/termux/termux-packages", "packaging_source", "meta", "Current Termux build recipes; reference for Android patches only."),
    ("Termux game-packages (archived)", "https://github.com/termux/game-packages", "packaging_source", "meta", "Archived and merged into termux-packages; historical reference only."),
    ("Shell Games", "https://github.com/MCookinho/shellgame", "collection", "arcade", "25 bash games in one repository; needs a bundled shell interpreter."),
]

SECTIONS = {
    "arcade": [
        ("0verkill", "https://github.com/hackndev/0verkill"),
        ("nInvaders", "https://ninvaders.sourceforge.net/"),
        ("ASCII Invaders", "https://github.com/macdice/ascii-invaders"),
        ("UNIX Aliens", "https://github.com/troglobit/aliens"),
        ("Alienwave", "https://www.alessandropira.org/alienwave/aw.html"),
        ("Just Asteroids", "https://github.com/gabrielvictorcf/just-asteroids"),
        ("Awkaster (raycasting)", "https://github.com/TheMozg/awk-raycaster"),
        ("DOOM-ASCII", "https://github.com/wojciech-graj/doom-ascii"),
        ("Wolfentext3D", "https://github.com/AtomicPair/wolfentext3d"),
        ("Shell Tanks", "https://github.com/annacrombie/shell-tanks"),
        ("piu-piu-SH", "https://github.com/vaniacer/piu-piu-SH"),
        ("Astwar", "https://savannah.nongnu.org/projects/astwar"),
        ("Terminal Phase", "https://gitlab.com/dustyweb/terminal-phase"),
        ("ft_retro", "https://gitlab.com/Heagan/ft_retro"),
        ("Terminal Frogger", "https://github.com/mfey619/Terminal-Frogger"),
        ("Moon Buggy", "https://www.seehuhn.de/pages/moon-buggy"),
        ("Gnuski", "https://sourceforge.net/projects/gnuski/"),
        ("MyMan (Pac-Man)", "https://myman.sourceforge.io/"),
        ("UniPac", "https://github.com/jesper-olsen/UniPac"),
        ("ASCII Pacman", "https://github.com/MarquisdeGeek/pacman"),
        ("ASCII Patrol", "http://ascii-patrol.com/"),
        ("Termrex (Dino)", "https://github.com/SATYADAHAL/termrex"),
        ("Terminal Pong", "https://github.com/IshmamR/terminal.pong"),
        ("Oldrunner", "http://culot.org/public/Code/oldrunner.html"),
        ("ASCII Dash", "https://sourceforge.net/projects/ascii-dash/"),
        ("Shellshock", "https://dhampir.no/stuff/bash/shellshock"),
        ("Cavez of Phear", "https://github.com/AMDmi3/cavezofphear"),
        ("Tornado", "https://github.com/kouya/tornado"),
        ("Xonitix", "https://github.com/garu/Xonitix"),
        ("ASCIIpOrtal", "https://github.com/cymonsgames/ASCIIpOrtal"),
        ("Bashmaze", "https://github.com/phoemur/bashmaze"),
        ("Cemetery Escape", "https://github.com/tom-on-the-internet/cemetery-escape"),
        ("Lightcycle", "https://github.com/gsobell/lightcycle"),
    ],
    "puzzle": [
        ("2048.c", "https://github.com/mevdschee/2048.c"),
        ("2048-cli", "https://github.com/Tiehuis/2048-cli"),
        ("PyThrees", "https://github.com/yth/pythrees"),
        ("Threes C", "https://github.com/harshjv/threes-c"),
        ("Bastet", "https://github.com/fph/bastet"),
        ("TinyTetris", "https://github.com/taylorconor/tinytetris"),
        ("Tetro TUI", "https://github.com/Strophox/tetro-tui"),
        ("CTRIS", "https://github.com/dominikhackl/ctris"),
        ("Vitetris", "http://victornils.net/tetris/"),
        ("ASCII-Tetris", "https://github.com/Gregwar/ASCII-Tetris"),
        ("Yetris", "https://alexdantas.github.io/yetris/"),
        ("Tetris (berk76)", "https://github.com/berk76/tetris"),
        ("TinyCols", "https://github.com/zedr/tinycols"),
        ("C-Netris", "https://github.com/naclander/netris"),
        ("Nsnake", "https://sourceforge.net/projects/nsnake/"),
        ("ASCII Snake", "https://github.com/cyphar/ascii-snake"),
        ("MSnake", "https://github.com/mogria/msnake"),
        ("VIper Snake", "https://gir.st/viper.html"),
        ("Nibbles", "https://www.earth.li/projectpurple/progs/nibbles.html"),
        ("Cnibbles", "http://cnibbles.sourceforge.net"),
        ("Npush", "http://npush.sourceforge.net/"),
        ("Katamascii", "https://github.com/taviso/katamascii"),
        ("Chroma", "http://www.level7.org.uk/chroma/"),
        ("FloodIt", "https://github.com/smmspiers/FloodIt"),
        ("Clines", "https://github.com/veselov/clines"),
        ("Nuzzle", "https://github.com/dead-end/nuzzle"),
        ("Brickgame 4bit", "https://github.com/ilyakurdyukov/brickgame-4bit"),
        ("Zigtris", "https://github.com/ringtailsoftware/zigtris"),
        ("Nudoku", "https://jubalh.github.io/nudoku/"),
        ("NSudoku", "https://www.tbmatuka.com/nsudoku/"),
        ("NSuds", "https://sourceforge.net/projects/nsuds/"),
        ("Sudoku-rs", "https://github.com/MitchelPaulin/sudoku-rs"),
        ("Minesweep-rs", "https://github.com/cpcloud/minesweep-rs"),
        ("Anonymine", "https://oskog97.com/projects/anonymine/"),
        ("Freesweep", "https://github.com/rwestlund/freesweep"),
        ("Minecurses", "https://github.com/christosmarg/minecurses"),
        ("MinesVIiper", "https://gir.st/mines.html"),
        ("Minesweeper 4D", "https://github.com/itabesamesa/minesweeper_4d_rs"),
        ("Connect4", "https://github.com/badescunicu/connect4"),
        ("Morpion Solitaire", "https://github.com/gre/morpion-solitaire"),
        ("Zoridor", "https://github.com/ringtailsoftware/zoridor"),
        ("Rhex (hex puzzle/roguelike)", "https://github.com/dpc/rhex"),
        ("Penney's Game", "https://github.com/sepandhaghighi/penney"),
        ("ASCII-Chess", "https://github.com/Parigyan/ASCII-Chess"),
    ],
    "roguelike": [
        ("NetHack", "https://github.com/NetHack/NetHack"),
        ("Brogue Community Edition", "https://github.com/tmewett/BrogueCE"),
        ("Dungeon Crawl Stone Soup", "https://github.com/crawl/crawl"),
        ("Cataclysm: Dark Days Ahead", "https://github.com/CleverRaven/Cataclysm-DDA"),
        ("Angband", "https://rephial.org/"),
        ("ADOM", "https://www.adom.de/"),
        ("DoomRL / DRL", "https://drl.chaosforge.org/"),
        ("AlienRL", "https://alien.chaosforge.org/"),
        ("Bash Dungeon", "https://github.com/wolandark/bash-dungeon"),
        ("Moria", "https://umoria.org/"),
        ("Omega", "http://www.alcyone.com/max/projects/omega/"),
        ("SporkHack", "https://github.com/NHTangles/sporkhack"),
        ("Hydra Slayer", "http://www.roguetemple.com/z/hydra/"),
        ("GearHead", "http://www.gearheadrpg.com/"),
        ("Frozen Depths", "https://frozendepths.net/"),
        ("Rogue (BSD)", "https://man.netbsd.org/rogue.6"),
        ("TROG", "https://github.com/JohnAnthony/TROG"),
        ("Ularn", "https://github.com/joshbressers/ularn"),
    ],
    "adventure": [
        ("Pokete", "https://github.com/lxgr-linux/pokete"),
        ("Open Adventure", "https://gitlab.com/esr/open-adventure"),
        ("Robot Finds Kitten", "http://robotfindskitten.org/"),
        ("Oregon Trail", "https://github.com/yudonglin/Oregon-Trail"),
        ("Dukedomrb", "https://github.com/infews/dukedomrb"),
        ("Wander", "https://github.com/shmup/wander"),
        ("Stugan", "https://github.com/jhbaarnh/stugan"),
        ("Frotz (Z-machine interpreter)", "https://davidgriffith.gitlab.io/frotz/"),
        ("dMagnetic (interactive fiction interpreter)", "https://www.dettus.net/dMagnetic"),
        ("NBSD Games (18 games)", "https://github.com/abakh/nbsdgames"),
        ("Spire Ascent (Shell Games)", "https://github.com/MCookinho/shellgame"),
    ],
    "strategy": [
        ("Chess TUI", "https://github.com/thomas-mauran/chess-tui"),
        ("NChess", "https://github.com/billyvinning/nchess"),
        ("CHS", "https://github.com/nickzuber/chs"),
        ("GNU Chess", "https://www.gnu.org/software/chess/"),
        ("GNU Go", "https://www.gnu.org/software/gnugo/"),
        ("Dango (Go board)", "https://github.com/gsobell/dango"),
        ("GNU Shogi", "https://www.gnu.org/software/gnushogi/"),
        ("Curse of War", "https://a-nikolaev.github.io/curseofwar/"),
        ("LeTrain", "https://github.com/antoniovazquezaraujo/LeTrain"),
        ("Conquest (Beej)", "https://github.com/beejjorgensen/conquest"),
        ("Conquest (Jtrulson)", "https://github.com/jtrulson/conquest/"),
        ("Spacezero", "http://spacezero.sourceforge.net/"),
        ("Starlanes", "http://www.barnsdle.demon.co.uk/game/starlanes.html"),
        ("Hamurabi", "https://github.com/blt/hamurabi"),
        ("Hammurabi Python", "https://github.com/wzhishen/hammurabi"),
        ("Dopewars", "https://dopewars.sourceforge.io/"),
        ("Rebels in the Sky", "https://github.com/ricott1/rebels-in-the-sky"),
    ],
    "simulation": [
        ("Terminal Space Program", "https://github.com/jasonfen/terminal-space-program"),
        ("Solar System Explorer", "https://github.com/furan917/go-solar-system"),
        ("Inertia (physics simulation)", "https://github.com/aclfe/inertia"),
    ],
    "cards": [
        ("TTY Solitaire", "https://github.com/mpereira/tty-solitaire"),
        ("CSol", "https://github.com/nielssp/csol"),
        ("SolVItaire", "https://gir.st/sol.html"),
        ("Freecell-NG", "https://github.com/ostrosablin/freecell"),
        ("BalatroTUI", "https://github.com/Passeriform/BalatroTUI"),
        ("Blackguard", "https://github.com/ElnurBDa/blackguard"),
        ("Rummy", "https://github.com/ljc2154/Rummy"),
        ("Backgammon BSD", "https://man.openbsd.org/backgammon.6"),
        ("Cribbage BSD", "https://man.openbsd.org/cribbage.6"),
        ("Mille BSD", "https://man.openbsd.org/mille.6"),
        ("Monop BSD", "https://man.openbsd.org/monop.6"),
        ("Blue Moon Solitaire", "http://www.catb.org/~esr/bluemoon/"),
        ("Cpat Solitaire", "http://cpat.sourceforge.net/"),
    ],
    "words": [
        ("GitType", "https://github.com/unhappychoice/gittype"),
        ("TTYper", "https://github.com/max-niederman/ttyper"),
        ("TypeSpeed", "http://typespeed.sourceforge.net/"),
        ("Clidle", "https://github.com/ajeetdsouza/clidle"),
        ("Wordle CLI", "https://github.com/nimblebun/wordle-cli"),
        ("Hangman BSD", "https://man.openbsd.org/hangman.6"),
        ("Boggle BSD", "https://man.openbsd.org/boggle.6"),
        ("Wump BSD", "https://man.openbsd.org/wump.6"),
        ("SSHTron", "https://github.com/zachlatta/sshtron"),
        ("Onx (online tic-tac-toe)", "https://github.com/jerkyworks/onx"),
        ("SSH Hattrick", "https://github.com/ricott1/sshattrick"),
        ("Late.sh (includes games)", "https://github.com/mpiorowski/late-sh"),
        ("Roguelike Gallery", "https://rlgallery.org"),
        ("Telehack Z-Code games", "http://telehack.com/telehack.html"),
        ("SSH games (Milek7)", "https://github.com/Milek7/telnet-games"),
    ],
}

ONLINE = {"SSHTron", "Onx (online tic-tac-toe)", "SSH Hattrick", "Late.sh (includes games)", "Roguelike Gallery",
          "Telehack Z-Code games", "SSH games (Milek7)"}
INTERPRETERS = {"Frotz (Z-machine interpreter)", "dMagnetic (interactive fiction interpreter)"}
COLLECTIONS = {"NBSD Games (18 games)"}
SIMULATIONS = {"Solar System Explorer", "Inertia (physics simulation)"}

SHELL_GAMES = [
    "2048", "Asteroids", "Blackjack", "Breakout", "Centipede", "Checkers", "Chess", "Dig Dug", "Donkey Kong",
    "Duck Hunt", "Enduro", "Frogger", "Galaga", "Hangman", "Minesweeper", "Pac-Man", "Pong", "Q*Bert",
    "Rhythm Spire", "Snake", "Solitaire", "Spire Ascent", "Super Auto Pets", "Tetris", "The Typer",
]


def slug(s):
    s = re.sub(r"\(.*?\)", "", s).lower().replace("&", "and").replace("'", "")
    s = re.sub(r"[^a-z0-9]+", "-", s).strip("-")
    return s[:60] or "entry"


def entry(title, url, kind, category, notes, eid=None):
    eid = eid or slug(title)
    status, license_, game_id = "CANDIDATE", "UNKNOWN", None
    if eid in PORTED:
        game_id, license_ = PORTED[eid]
        status = "PORTABLE"
    if kind == "online_service":
        notes = (notes + " " if notes else "") + "Online service: would need an opt-in, clearly labeled network mode."
    return {
        "id": eid, "title": title, "source_url": url, "kind": kind, "category": category, "status": status,
        "upstream_license": license_, "gameplay_actions": [], "expected_terminal_size": [],
        "preferred_orientation": None, "control_profile": None,
        "backend": "native_pty" if game_id else "undecided", "platform_support": None,
        "import_date": IMPORTED, "last_checked": IMPORTED if game_id else None, "version": None,
        "dependencies": [], "screenshot_provenance": None,
        "build_recipe": f"native/build.py#{game_id}" if game_id else None,
        "game_id": game_id,
        "notes": notes or ("Ported to Tuikio; see the game manifest." if game_id else "Not yet assessed: license, build and controls unverified."),
    }


def main():
    out, seen = [], set()
    for title, url, kind, cat, notes in SOURCES:
        e = entry(title, url, kind, cat, notes, eid=slug(title) + ("-src" if kind != "collection" else ""))
        out.append(e)
    for cat, items in SECTIONS.items():
        for title, url in items:
            kind = ("online_service" if title in ONLINE else "interpreter" if title in INTERPRETERS
                    else "collection" if title in COLLECTIONS else "simulation" if title in SIMULATIONS else "game")
            eid = slug(title)
            if eid in seen:  # e.g. two different "Conquest" projects: keep the qualifier
                eid = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")
            seen.add(eid)
            e = entry(title, url, kind, cat, "", eid=eid)
            if title == "2048.c":
                e = entry(title, url, kind, cat, "", eid="2048-c")
            if title == "Robot Finds Kitten":
                e = entry(title, url, kind, cat, "", eid="robot-finds-kitten")
            out.append(e)
    # Added by the Tuikio team (not in the brief): ported because it suits touch controls.
    greed = entry("Greed", "http://www.catb.org/~esr/greed/", "game", "puzzle",
                  "Added by Tuikio from Ubuntu's archive; ported because eight-way moves suit a touch pad.", eid="greed")
    greed.update(status="PORTABLE", upstream_license="BSD-2-Clause", game_id="greed", backend="native_pty",
                 last_checked=IMPORTED, build_recipe="native/build.py#greed")
    out.append(greed)
    for name in SHELL_GAMES:
        e = entry(f"{name} (Shell Games)", "https://github.com/MCookinho/shellgame", "game", "arcade",
                  "One of 25 bash games in MCookinho/shellgame; each needs its own license, controls and interpreter assessment.",
                  eid="shellgame-" + slug(name))
        out.append(e)
    ids = [e["id"] for e in out]
    dups = {i for i in ids if ids.count(i) > 1}
    if dups:
        raise SystemExit(f"duplicate ids: {sorted(dups)}")
    with open(os.path.join(ROOT, "catalog", "discovery.json"), "w") as fh:
        json.dump(out, fh, indent=2, ensure_ascii=False)
        fh.write("\n")
    urls = {}
    for e in out:
        urls.setdefault(e["source_url"].rstrip("/").lower(), []).append(e["id"])
    shared = {u: v for u, v in urls.items() if len(v) > 1}
    print(f"{len(out)} entries; {len(urls)} distinct URLs; shared URLs: {len(shared)}")


if __name__ == "__main__":
    main()
