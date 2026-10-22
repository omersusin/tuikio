# Architecture

```
app/                    Android app (framework Views, no AndroidX)
  ui/                   design system: tokens, brand packs, Theme, StickerDrawable, widgets, motion
  nav/                  Navigator (screen stack) + Dock (four tabs)
  screens/              Discover, Search, Details, Library, Create, Publish, You, Brand Studio, About, Diagnostics
  game/                 GameActivity, TerminalView (renderer), ControlsView (touch controls)
  data/                 CatalogRepo (bundled + signed sync), Library/Installer, Sources, Prefs, AppConfig
  community/            GitHub sign-in (PKCE), submission client, OAuth callback
  brand/                launcher identities (activity-aliases), custom logo, pinned shortcuts
core/terminal           VT500/xterm emulator, Unicode widths, key and mouse encoders
core/controls           control profiles (JSON) + dispatcher (repeat, confirm, sequences)
core/catalog            JSON reader, game manifests, discovery entries, source URL validation, signatures
core/runtime            GameSession (process + PTY frames + emulator), save archives
core/community          submission model, proof of work, receipts, PKCE, GitHub REST client
backend/                submission server (JDK HttpServer)
tools/catalog-cli       validate / assemble / sign the catalog
native/                 PTY helper, original game, ports, build.py, smoke tests
catalog/                manifests, control profiles, discovery list, schemas
design/                 brand sources (mascot, marks, wordmark), fonts
build-logic/            Gradle plugin that builds the APK with the SDK tools directly
```

The `core/*` modules are plain Kotlin/JVM (Java 8 bytecode). The same code runs in the app,
in the backend, and in host tests that drive the real game binaries.

## Playing a game

1. **Discover / Details** read `CatalogRepo`: the bundled `catalog.json`, or a newer signed
   one. A game is playable when its binary is in this APK and its status is at least `PORTABLE`.
2. **Install** (`Installer`) checks free space, checks the binary's SHA-256 against
   `assets/native/manifest.json`, and copies data files into `files/games/<id>/home` with
   hash checks. Failures show a reason and a code (`TK-ABI`, `TK-SPACE`, `TK-SUM`, `TK-DATA`,
   `TK-IO`).
3. **GameActivity** sizes the terminal to the screen (font scale, min/preferred size from the
   manifest), builds the controls from the profile, and starts a `GameSession`.
4. **GameSession** starts `libtuikio_pty.so` with the game (see `RUNTIME.md`). A reader thread
   feeds output to the `TerminalEmulator`. Input from touch controls, gestures, the
   keyboard/IME, gamepads and mouse taps is encoded to xterm sequences and sent as frames.
5. **TerminalView** redraws only damaged rows into a bitmap on the next frame. The cursor
   blinks only while the game is in the foreground. Accessibility exposes the screen text.
6. Leaving the screen sends SIGSTOP, returning sends SIGCONT, and closing sends SIGHUP and
   then SIGKILL if needed.

## Design system

Tuikio's look is its own rather than Material:
- "sticker" cards with ink outlines and offset shadows (`StickerDrawable`);
- a pebble switch, stripe progress and a floating dock;
- Unbounded for display text, Figtree for UI text, JetBrains Mono for terminals.

Everything is drawn programmatically from tokens (`ui/Tokens.kt`).

Brand packs (Tuikio, Tuiko, PockTTY, Tuitopia, Bitiko, Ttybit, plus the Phosphor accessibility
pack) set the palette, type, shape, motion and terminal colors. Theme handles light, dark and
system modes, high contrast, and reduced motion (system animator scale, power saver and
thermal throttling).

**Brand Studio** applies a pack in the app at once. It switches the launcher identity by
enabling one of six `activity-alias` entries (atomically on API 33+). Custom names and logos
can't change the installed app's label, so they become a pinned shortcut that the user
approves. The package name, signing key, saves and accounts never change with branding.

## Security boundaries

- **No downloaded code.** Executables come only from the APK's `nativeLibraryDir`, named by a
  strict pattern.
- **Untrusted input everywhere is bounded.** JSON parsing has size, depth and string limits.
  Save import has path, entry, size and ratio limits. Logo import has a pixel cap. Network
  reads are capped.
- **Remote catalogs** must carry an ECDSA P-256 signature from the key pinned in the build,
  and a newer version number.
- **Source links** are validated on the phone and again on the server. The server's fetcher
  connects only to public addresses it checked itself.
- **Secrets**: the APK contains no client secrets or write tokens. Signing keys stay out of
  the repository and CI (`RELEASE.md`).
