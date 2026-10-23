# Game runtime

Tuikio runs real terminal programs: C and C++ games compiled for Android, connected to a
terminal emulator written in Kotlin. Nothing is simulated or re-implemented.

## Why games ship inside the APK

Since Android 10, apps cannot execute files they wrote themselves (W^X for app data:
`execve` on files in the app's data directory is denied for apps targeting API 29+). So the
"download a binary, `chmod +x`, run it" approach is both forbidden and unsafe. Tuikio never
executes anything it downloaded.

Instead, every game is built ahead of time and packaged as a native library:

- `native/build.py` builds each game as a **static-PIE executable** for arm64-v8a,
  armeabi-v7a and x86_64 (Zig's clang + musl libc, `-static -fPIE`, RELRO/BIND_NOW,
  `-z max-page-size=16384` for 16 KB-page devices, stripped).
- Each executable is named `lib<name>.so` and placed under `lib/<abi>/` in the APK.
  `android:extractNativeLibs="true"` makes the package installer extract them into the
  app's read-only, executable `nativeLibraryDir`. Only the installer writes there, and
  executing from there is allowed.
- ncurses is linked statically with compiled-in terminfo fallbacks (`xterm-256color`,
  `xterm`, `vt100`, `linux`, `dumb`), so no terminfo files are needed on the device.
- The catalog can only name executables matching `^game_[a-z0-9_]{1,40}$`. They resolve to
  `nativeLibraryDir/lib<executable>.so` and nowhere else (`LaunchSpec.forManifest`).

"Installing" a game means preparing its private home directory: copying data files from
assets (each checked against a SHA-256) and checking the binary against
`assets/native/manifest.json`. No code is downloaded.

## Process model

```
GameActivity ── GameSession ── ProcessBuilder ──► libtuikio_pty.so <cols> <rows> libgame_x.so [args]
     ▲   │                         stdin: frames             │ forkpty()
     │   └── TerminalEmulator ◄─── stdout: raw terminal ◄────┘ game on a real PTY
TerminalView  ControlsView/keyboard/gamepad ─► ControlDispatcher ─► KeyEncoder ─► T_INPUT frames
```

`libtuikio_pty.so` (`native/pty/tuikio_pty.c`) is a small helper. It opens a pseudo-terminal
with `forkpty`, starts the game on it, and relays bytes:

- **stdin** carries frames `[type u8][length u16 BE][payload]`:
  `1` input bytes, `2` resize (`cols u16, rows u16` → `TIOCSWINSZ`, so the game gets
  `SIGWINCH`), `3` signal (`1` INT, `2` HUP, `3` TERM, `4` CONT, `5` STOP, sent to the game's
  process group).
- **stdout** is the game's raw terminal output.
- **stderr** has the helper's own messages, plus a status line `@mode line` or `@mode keys`
  whenever the game switches its terminal between canonical (line) input and single keys. The
  game screen uses it for answer chips: in line mode an answer is followed by Enter, in key
  mode it is sent alone (an extra Enter would answer the next question).
- **Output coalescing**: after the first chunk, the helper collects whatever follows within
  4 ms and writes it once. Games that draw a frame as many tiny writes then wake the app once
  per frame instead of dozens of times (see `docs/PERFORMANCE.md`).
- **Lifecycle**: EOF on stdin means the app is gone. The helper sends SIGHUP, waits briefly,
  then SIGKILL. The game also has `PR_SET_PDEATHSIG`. The helper exits with the game's exit
  status, or 128 + signal.

The app sends SIGSTOP when the game screen leaves the foreground (`onStop`) and SIGCONT
when it returns. A paused game uses no CPU and keeps its state.

## Environment

Every game starts with a cleared environment (`GameEnvironment`):

| Variable | Value |
|---|---|
| `HOME`, working directory | `files/games/<id>/home` (the game's saves) |
| `TMPDIR` | `$HOME/.tmp` |
| `TERM` / `COLORTERM` | `xterm-256color` / `truecolor` |
| `LANG`, `LC_ALL` | `C.UTF-8` |
| `USER`, `LOGNAME` | `player` |
| `SHELL`, `PATH` | `/nonexistent`: there is no shell to escape into |
| `ESCDELAY` | `40` (ms) so ESC is told apart from arrow-key prefixes quickly |

Manifests may add variables but cannot override these, and cannot set anything starting with `LD_`.

Games run with the app's own Linux user and SELinux domain. They can read their own home and
nothing outside the app's sandbox. Network access is technically possible because the app
holds `INTERNET`, so the catalog records each game's `network` use (`none` for all current
games), and reviewers check it before a game can reach STORE_READY.

## Terminal emulator

`core/terminal` is an xterm-compatible VT500-series parser:

- UTF-8 decoding, East Asian wide characters and combining marks (tables generated from
  Unicode data by `tools/gen_width_table.py`).
- Alternate screen (47/1047/1049), scroll regions, DEC special graphics, insert/delete.
- SGR with 16, 256 and 24-bit color, including colon forms.
- DSR/DA replies, bracketed paste, mouse modes 9/1000/1002/1003 with 1005/1006/1015 encodings.
- Per-row damage tracking, so `TerminalView` only redraws rows that changed.

Cell attributes are packed into one `Long` per cell, which keeps a screen allocation-free
while a game runs.

## Saves

Saves are whatever files the game writes in `$HOME`. "Export saves" on a game's page writes a
zip through the system file picker, and import restores one (`SaveArchive`). Import is
defensive:

- every path is checked to be relative and inside the game's home;
- at most 2000 entries, 16 MB per file and 64 MB in total;
- compression ratio is capped at 200:1 for files over 1 MB (zip bombs);
- a marker file must match the game id;
- nothing replaces the current saves until the whole archive has been read into a temporary
  directory, which then replaces the old saves with one rename.

Android backup uses `data_extraction_rules.xml` (API 31+) and `backup_rules.xml` (older). Game homes are included,
and the GitHub sign-in is excluded.

## Adding a game

1. Pin its source in `native/sources.lock.json` (URL + SHA-256), add a `build_<game>()`
   recipe and any patches under `native/ports/<game>/patches/`. Data paths must be relative
   to `$HOME`, and the game must not need a shell or other programs.
2. Add a license entry to `NOTICES` in `native/build.py` so its license ships in the APK.
3. Write `catalog/games/<id>.json` and a control profile (`docs/CATALOG.md`).
4. Build, then add a host test in `core/runtime/.../RealGamesTest.kt` that drives it.
5. Status stays `PORTABLE` until someone plays it on Android and records the evidence in
   `verification.android_tested`.
