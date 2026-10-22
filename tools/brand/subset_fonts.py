#!/usr/bin/env python3
"""Subsets the display font for the APK. design/fonts/ keeps the full originals; the app
gets Latin-script coverage (Basic Latin through Latin Extended-B, plus punctuation,
currency, arrows and a few symbols used in the UI). Text outside these ranges still renders:
Android falls back to system fonts per character. All variation axes are kept.

Unbounded is under the SIL OFL 1.1 without a Reserved Font Name, so a subset may keep its name.

    /opt/tuikio-tools/venv/bin/python tools/brand/subset_fonts.py
"""
import os
from fontTools import subset

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
RANGES = [
    (0x0020, 0x024F),  # Basic Latin, Latin-1, Latin Extended-A/B (incl. Turkish ı ğ ş İ)
    (0x02C6, 0x02DD),  # spacing modifiers (circumflex, tilde, ring…)
    (0x0300, 0x036F),  # combining diacritics
    (0x1E00, 0x1EFF),  # Latin Extended Additional (Vietnamese, Welsh…)
    (0x2000, 0x206F),  # general punctuation (– — ‘ ’ “ ” • … ›)
    (0x20A0, 0x20CF),  # currency
    (0x2100, 0x215F),  # letterlike symbols, number forms
    (0x2190, 0x21FF),  # arrows
    (0x2212, 0x2212),  # minus
]


def main():
    src = os.path.join(ROOT, "design", "fonts", "Unbounded-Variable.ttf")
    dst = os.path.join(ROOT, "app", "src", "main", "assets", "fonts", "Unbounded-Variable.ttf")
    opts = subset.Options()
    opts.layout_features = ["*"]
    opts.name_IDs = ["*"]
    opts.name_languages = ["*"]
    opts.notdef_outline = True
    opts.hinting = False
    opts.drop_tables += ["DSIG"]
    font = subset.load_font(src, opts)
    s = subset.Subsetter(opts)
    s.populate(unicodes=[cp for a, b in RANGES for cp in range(a, b + 1)])
    s.subset(font)
    subset.save_font(font, dst, opts)
    print(f"{os.path.basename(dst)}: {os.path.getsize(src):,} -> {os.path.getsize(dst):,} bytes")


if __name__ == "__main__":
    main()
