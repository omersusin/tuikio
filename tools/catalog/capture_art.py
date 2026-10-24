#!/usr/bin/env python3
"""Card pictures from the games' real screens.

RenderShotsTest (./gradlew :app:test --tests '*RenderShotsTest*' -Pshots=primary) leaves every
game's screen, cell by cell, in app/build/shots/<id>-<land|port>.screen.json. This picks the
liveliest few rows of each (box and block drawing, colour, the board rather than prose) and
writes them to the game's manifest as "art", which the app draws on its cards.

    python3 tools/catalog/capture_art.py            # every game with a shot
    python3 tools/catalog/capture_art.py greed      # some
"""
import glob
import json
import os
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
SHOTS = os.path.join(ROOT, "app", "build", "shots")
GAMES = os.path.join(ROOT, "catalog", "games")
CODES = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
# Designed pictures win over captured ones.
KEEP = {"lantern-tide"}
WINDOWS = [(22, 6), (26, 7), (30, 8)]
# Where the liveliest rows aren't the game (a help column, a title box): the part of the screen
# that is, as (row, col, width, height). Cards fit about three columns per row; taller parts
# are drawn small. SQUASH drops its rows without a glyph (boards drawn
# double-spaced).
PIN = {
    "2048": (2, 0, 28, 12),
    "arithmetic-bsd": (0, 0, 14, 1),
    "ascii-snake": (3, 0, 32, 11),
    "ascii-tetris": (12, 0, 26, 9),
    "backgammon-bsd": (0, 0, 29, 9),
    "cribbage-bsd": (0, 0, 24, 8),
    "empire": (11, 40, 40, 8),
    "freesweep": (5, 0, 38, 12),
    "miketron-nb": (3, 0, 44, 11),
    "mille-bsd": (5, 0, 40, 13),
    "moon-buggy": (16, 46, 34, 6),
    "morpion-solitaire": (13, 18, 43, 19),
    "nettoe": (3, 0, 27, 8),
    "pipes-nb": (0, 0, 29, 8),
    "petris": (2, 4, 41, 14),
    "phantasia-bsd": (0, 14, 40, 1),
    "robots-bsd": (0, 0, 48, 12),
    "snake-bsd": (5, 46, 26, 7),
    "teachgammon-bsd": (0, 0, 29, 9),
    "tetris-bsd": (11, 27, 24, 10),
    "tint": (12, 26, 24, 10),
    "trader": (4, 0, 44, 15),
    "trsr-nb": (3, 0, 31, 10),
    "worm-bsd": (8, 0, 26, 8),
    "xonitix": (1, 20, 48, 1),
}
SQUASH = {"morpion-solitaire"}


def cell_score(ch, fg, bg, in_word, base_bg):
    s = 0.0
    if ch.strip():
        cp = ord(ch[0])
        if 0x2500 <= cp <= 0x259F or 0x2800 <= cp <= 0x28FF:
            s = 1.3
        elif ch.isalnum():
            s = 0.3 if in_word else 0.8
        else:
            s = 1.0
        if fg not in ("d", "i"):
            s += 0.5
    # A board's own colour counts a little; anything painted on it (tiles, pieces) a lot.
    if bg != "d":
        s += 0.3 if bg == base_bg else 1.4
    return s


def scores(screen):
    from collections import Counter
    bgs = Counter(c[2] for row in screen["cells"] for c in row if c[2] != "d")
    base_bg = bgs.most_common(1)[0][0] if bgs else None
    out = []
    for row in screen["cells"]:
        chars = [c[0] or " " for c in row]
        # Letters inside words of four or more: prose, not picture.
        word = [False] * len(chars)
        i = 0
        while i < len(chars):
            j = i
            while j < len(chars) and chars[j].isalpha():
                j += 1
            if j - i >= 4:
                for k in range(i, j):
                    word[k] = True
            i = max(j, i + 1)
        out.append([cell_score(chars[c], row[c][1], row[c][2], word[c], base_bg) for c in range(len(row))])
    return out


def best_window(screen, sc):
    rows, cols = len(sc), len(sc[0])
    chars = [[c[0] or " " for c in row] for row in screen["cells"]]
    fgs = [[c[1] for c in row] for row in screen["cells"]]
    pre = [[0.0] * (cols + 1) for _ in range(rows + 1)]
    for r in range(rows):
        for c in range(cols):
            pre[r + 1][c + 1] = sc[r][c] + pre[r][c + 1] + pre[r + 1][c] - pre[r][c]

    def cut(r, c):  # a word broken between columns c-1 and c
        return 0 < c < cols and chars[r][c - 1].isalnum() and chars[r][c].isalnum()

    best = None
    for w, h in WINDOWS:
        if w > cols or h > rows:
            continue
        for r in range(rows - h + 1):
            for c in range(cols - w + 1):
                total = pre[r + h][c + w] - pre[r][c + w] - pre[r + h][c] + pre[r][c]
                if total < 6:
                    continue
                cuts = sum(cut(y, c) + cut(y, c + w) for y in range(r, r + h))
                colours = len({fgs[y][x] for y in range(r, r + h) for x in range(c, c + w)} - {"d", "i"})
                value = (total - 1.5 * cuts) / (w * h) ** 0.75 + 0.35 * min(colours, 6)
                if best is None or value > best[0]:
                    best = (value, r, c, w, h, total)
    return best


def art_from(screen, r0, c0, w, h, squash=False):
    palette, rows, fgs, bgs = [], [], [], []

    def code(col, none):
        if col == none:
            return "."
        if col not in palette:
            palette.append(col)
        return CODES[palette.index(col)]

    for r in range(r0, r0 + h):
        text, fg, bg = [], [], []
        for c in range(c0, c0 + w):
            ch, f, b = screen["cells"][r][c]
            wide = ch == "" or (ch and ord(ch[0]) >= 0x1100 and is_wide(ch))
            if not ch or wide or ord(ch[0]) < 0x20:
                ch = " "
            text.append(ch[0])
            fg.append(code(f, "d"))
            bg.append(code(b, "d"))
        rows.append("".join(text))
        fgs.append("".join(fg))
        bgs.append("".join(bg))
    if squash:
        keep = [i for i in range(len(rows)) if rows[i].strip()]
        rows, fgs, bgs = [rows[i] for i in keep], [fgs[i] for i in keep], [bgs[i] for i in keep]
    # Trim blank columns at both ends and blank rows, keeping at least the core.
    def blank_col(c):
        return all(rows[r][c] == " " and bgs[r][c] == "." for r in range(len(rows)))

    while len(rows[0]) > 8 and blank_col(0):
        rows, fgs, bgs = [x[1:] for x in rows], [x[1:] for x in fgs], [x[1:] for x in bgs]
    while len(rows[0]) > 8 and blank_col(len(rows[0]) - 1):
        rows, fgs, bgs = [x[:-1] for x in rows], [x[:-1] for x in fgs], [x[:-1] for x in bgs]
    while len(rows) > 3 and rows[0].strip() == "" and bgs[0].strip(".") == "":
        rows, fgs, bgs = rows[1:], fgs[1:], bgs[1:]
    while len(rows) > 3 and rows[-1].strip() == "" and bgs[-1].strip(".") == "":
        rows, fgs, bgs = rows[:-1], fgs[:-1], bgs[:-1]
    if len(palette) > len(CODES) or len(rows) > 16 or len(rows[0]) > 48:
        print(f"too big for a card: {len(rows[0])}x{len(rows)}, {len(palette)} colours", file=sys.stderr)
        return None
    return {"rows": rows, "fg": fgs, "bg": bgs, "palette": palette}


def is_wide(ch):
    import unicodedata
    return unicodedata.east_asian_width(ch[0]) in ("W", "F")


def main():
    want = set(sys.argv[1:])
    done = []
    for f in sorted(glob.glob(os.path.join(GAMES, "*.json"))):
        m = json.load(open(f))
        gid = m["id"]
        if (want and gid not in want) or gid in KEEP:
            continue
        o = m["runtime"]["orientation"]
        shot = None
        for tag in (["port", "land"] if o == "portrait" else ["land", "port"]):
            p = os.path.join(SHOTS, f"{gid}-{tag}.screen.json")
            if os.path.exists(p):
                shot = json.load(open(p))
                break
        if shot is None:
            continue
        if gid in PIN:
            r, c, w, h = PIN[gid]
        else:
            best = best_window(shot, scores(shot))
            if best is None or best[5] < 12:
                print(f"{gid}: nothing to picture", file=sys.stderr)
                continue
            _, r, c, w, h, _ = best
        art = art_from(shot, r, c, w, h, squash=gid in SQUASH)
        if art is None:
            continue
        m["art"] = art
        # Keep the field order of the generator: art after keys, before saves.
        order = [k for k in m if k != "art"]
        i = order.index("saves")
        m = {k: m[k] for k in order[:i]} | {"art": art} | {k: m[k] for k in order[i:]}
        with open(f, "w") as fh:
            json.dump(m, fh, indent=2, ensure_ascii=False)
            fh.write("\n")
        done.append(gid)
    print(f"art for {len(done)} games")


if __name__ == "__main__":
    main()
