# Tuikio

Tuikio (tuıkıo) is an Android app for discovering, installing and playing terminal games:
the real C/C++ programs, running on a real pseudo-terminal, with touch controls designed for
each game. Its mascot is Kio, a hermit crab that lives in a terminal.

- **Real runtime.** Games are cross-compiled static executables packaged inside the APK. They
  run under a PTY helper and an xterm-compatible emulator written in Kotlin. Nothing is
  downloaded and executed (Android 10+ W^X), and nothing is simulated.
- **Controls per game.** Declarative profiles (D-pads, buttons, gestures, repeat, confirm for
  quit/restart), plus hardware keyboards, gamepads and mouse reporting.
- **Own look.** A custom design system (no Material/AndroidX), six brand packs plus an
  accessibility pack, and launcher identities you can switch in Brand Studio.
- **Community.** Save sources on your phone, suggest them anonymously or with GitHub credit,
  or publish your own game. Everything becomes a reviewed pull request.

## Status

| | |
|---|---|
| Games bundled | 9: Lantern Tide (original), 2048, vitetris, robotfindskitten, Moon-buggy, freesweep, nInvaders, greed, nsnake |
| Verification | All `PORTABLE`: built for arm64-v8a, armeabi-v7a and x86_64 and tested end to end on an x86_64 host (PTY protocol also checked under qemu for arm). **No device test has been recorded yet**, so none is `ANDROID_TESTED`, and the app labels them "Preview port". |
| Catalog | 189 discovery entries (181 candidates) |
| Visual direction | Design canvas awaiting the owner's review; not yet approved |
| Community server | Implemented and tested against a fake GitHub; not deployed |

## Build

```sh
python3 native/build.py              # games for all ABIs (needs zig 0.17)
./gradlew :app:assembleDebugApk      # app/build/outputs/apk/debug/tuikio-debug.apk
```

See [docs/BUILD.md](docs/BUILD.md). Other docs:
- [Architecture](docs/ARCHITECTURE.md)
- [Runtime](docs/RUNTIME.md)
- [Catalog format](docs/CATALOG.md)
- [Community server](docs/COMMUNITY.md)
- [Privacy](docs/PRIVACY.md)
- [Performance](docs/PERFORMANCE.md)
- [Releasing](docs/RELEASE.md)

## License

Tuikio is free software under the GNU GPL v3 or later ([LICENSE](LICENSE)). Each bundled game
keeps its own license (MIT, BSD-2-Clause, GPL-2.0-or-later or GPL-3.0-or-later) and credits its
authors first. License texts and exact source archives ship inside the app. Fonts are under
the SIL Open Font License 1.1.
