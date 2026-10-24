# Catalog

The catalog is plain JSON in `catalog/`, reviewed through pull requests:

```
catalog/
  games/<id>.json        playable ports: game manifests (schema/game-manifest.schema.json)
  profiles/<id>.json     control profiles (schema/control-profile.schema.json)
  discovery.json         every known link, playable or not (189 entries today)
  schema/                JSON Schemas for editors; the Kotlin validators are normative
```

Validate everything with:

```sh
./gradlew :tools:catalog-cli:run --args="validate $PWD/catalog"
```

`assemble` (run automatically by the app build) writes the bundled snapshot
`catalog.json` plus `profiles/`. If `TUIKIO_CATALOG_SIGNING_KEY` holds a base64 PKCS#8 EC
P-256 private key, it also writes `catalog.json.sig`. A hosted catalog is only accepted by
the app when its signature verifies against `catalog_public_key` in `assets/config.json`, and
only when its `catalog_version` is newer. Otherwise the bundled snapshot stays.

## Verification status

Status is about evidence, never hope:

| Status | Meaning | Can install? |
|---|---|---|
| `CANDIDATE` | A link worth looking at. Nothing checked. | No |
| `INVESTIGATING` | Someone is assessing license, build and controls. | No |
| `PORTABLE` | Builds for all ABIs and plays in the host runtime tests. | Bundled builds show it as a "Preview port" |
| `ANDROID_TESTED` | Played on a real device or emulator. `verification.android_tested` must record when and on what. | Preview |
| `STORE_READY` | Tested, licensed, reviewed. | Yes |

The validator rejects `ANDROID_TESTED` or `STORE_READY` without `android_tested` evidence.
Today all nine bundled games are `PORTABLE`: they pass host tests on x86_64 (and the PTY
protocol under qemu for arm), but nobody has recorded a device run yet. Settings ›
Diagnostics in the app runs a self-test on the phone, and its report is the kind of evidence
`android_tested` should reference.

## Game manifest (schema_version 1)

```jsonc
{
  "schema_version": 1,
  "id": "2048",                         // ^[a-z0-9][a-z0-9-]{1,47}$
  "title": "2048", "summary": "…", "description": "…",
  "category": "puzzle",                 // arcade|puzzle|roguelike|adventure|strategy|cards|words|simulation
  "tags": ["numbers"], "players": "1",
  "origin": "verified_port",            // official_upload|verified_port|community_submitted|tuikio_original
  "upstream": { "authors": ["Maurits van der Schee"], "license": "MIT",
                "source_url": "https://github.com/mevdschee/2048.c", "version": "…" },
  "port": { "maintainer": "…", "recipe": "native/build.py#2048",
            "source_archive": "2048.c", "source_sha256": "…" },
  "runtime": {
    "backend": "native_pty",
    "executable": "game_2048",          // ^game_[a-z0-9_]{1,40}$, bundled as lib<executable>.so
    "args": [], "env": {},              // no HOME/PATH/TERM/… overrides, nothing starting with LD_
    "terminal": { "min": [30, 15], "preferred": [40, 20] },
    "orientation": "portrait",          // portrait|landscape|any
    "wrap": "words",                    // optional: prose printed line by line wraps at words
    "input": "typing"                   // optional: played by typing, the keyboard starts up
  },
  "controls": "grid4.v1",               // a profile_id
  "control_labels": { "select": "Reveal" }, // optional: profile actions named for this game
  "control_hide": ["space"],            // optional: profile buttons this game never uses
  "keys": [                             // optional: the game's own keys, as labelled buttons
    { "label": "Bomb", "text": "b" },   //   under the game whenever it isn't asking a question
    { "label": "Menu", "key": "ESCAPE" }
  ],
  "art": {                              // optional: its card picture, rows of its real screen
    "rows": ["│▓▓ │", "└───┘"],         //   (tools/catalog/capture_art.py writes it)
    "fg": [".00..", "....."],           //   a colour code per cell: '.' the screen's ink,
    "bg": [".11..", "....."],           //   else an index into palette ('.' = no background)
    "palette": ["a9", "#203040"]        //   d, s, i, a0..a15 (theme), x16..x255, #rrggbb
  },
  "saves": { "paths": [], "notes": "…" },
  "data_files": [ { "asset": "games/x/data.bin", "dest": ".x/data.bin", "sha256": "…" } ],
  "network": "none",                    // none|optional|required
  "status": "PORTABLE",
  "verification": { "host_tested": "2026-10-22", "android_tested": null, "notes": "…" }
}
```

Every key a game uses in play needs a button: a profile action, or one of its `keys`. Keys the
screen names ("q - quit", "(n)ew game") become chips by themselves; keys it doesn't (Cavez of
Phear's b for a bomb) must be listed. The family tables in `tools/ports/families/` write them
compactly (`keys=["Bomb=b", "#1-9", "Next=<TAB>"]`).

Unknown fields are errors, so a typo never passes silently. Paths must be relative, with no
`..`. Licenses must be SPDX expressions. Authors come first: the details page always credits
the upstream authors before the port.

## Control profile (schema_version 1)

A profile declares **actions** (what a press sends) and **layouts** (where the controls sit),
separately for portrait and landscape:

```jsonc
{
  "schema_version": 1, "profile_id": "grid4.v1", "title": "Slide the grid", "help": "…",
  "actions": [
    { "id": "up", "label": "Up", "icon": "arrow_up", "output": { "key": "UP" } },
    { "id": "drop", "label": "Drop", "output": { "text": " " }, "haptic": "click" },
    { "id": "soft", "label": "Down", "output": { "key": "DOWN" },
      "repeat": { "delay_ms": 180, "interval_ms": 50 } },
    { "id": "quit", "label": "Quit", "output": { "sequence": [{ "text": "q" }, { "text": "y" }] },
      "confirm": true }
  ],
  "layout": {
    "portrait":  [{ "type": "dpad4", "anchor": "bottom_center", "size": 208,
                    "bind": { "up": "up", "down": "down", "left": "left", "right": "right" } }],
    "landscape": [{ "type": "button", "anchor": "bottom_end", "offset": [0, 0], "size": 60,
                    "bind": { "press": "quit" } }]
  },
  "gestures": { "swipe_up": "up" },     // swipe_*, double_tap, long_press
  "pointer": "none",                    // "mouse": taps become xterm mouse reports
  "keyboard": true                      // offer the on-screen keyboard
}
```

- `output` is exactly one of `key` (a `TermKey` name such as `UP`, `ENTER`, `F1`, `KP_5`),
  `text` (1–64 printable characters) or `sequence` (nested at most twice). `mods` may add
  `shift`, `alt` and `ctrl`.
- Element types are `dpad4`, `dpad8` (adds diagonals and `center`), `button` and `keyboard`.
  Positions are an anchor plus a dp offset and size, so layouts scale with the screen. Players
  can adjust size, opacity and handedness per game.
- `confirm: true` asks before sending, for quit and restart: one stray tap must never end a
  long game.
- Hardware keyboards always work alongside the profile and send real keys. On gamepads the
  D-pad, hat switch and left stick drive the profile's pad. A, B and X trigger the layout's
  first three buttons, Start opens the pause sheet, and Select shows the keyboard.

## Discovery entries

`discovery.json` lists every link from the project brief and from accepted community
suggestions (`tools/catalog/import_spec_links.py` regenerates the brief's part). Each entry
records `kind` (`game`, `collection`, `interpreter`, `engine`, `online_service`,
`discovery_list`, `simulation`, `packaging_source`), `status`, the upstream license if known,
and `game_id` when a playable port exists. Discovery entries are information, never installs:
the app links out to the source and offers "Suggest a port".
