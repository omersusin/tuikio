# Status report: Tuikio 0.1.0 (2026-10-22)

Statuses:
- **IMPLEMENTED_AND_TESTED**: built, and an automated or manual test was actually run.
- **IMPLEMENTED_UNTESTED**: built and compiles, but nothing has run it (mostly Android UI,
  because no device or emulator was available).
- **PARTIAL**: some of it works and is tested, some is missing.
- **BLOCKED**: cannot proceed without something outside this environment, usually the owner.

The environment had no Android device or emulator (no KVM) and no access to Google's Maven
repository.

| Feature | Status | Evidence | Next action |
|---|---|---|---|
| Native runtime: PTY helper, static-PIE musl games, W^X-compliant packaging as `lib*.so` in `nativeLibraryDir` | IMPLEMENTED_AND_TESTED | 11 end-to-end tests run the real x86_64 binaries through helper + frames + emulator (`RealGamesTest`). PTY smoke test passes on x86_64, arm64 and armv7 (qemu-user) | Run Diagnostics on an arm64 phone |
| 9 playable games (8 ports + original Lantern Tide), 3 ABIs | IMPLEMENTED_AND_TESTED (host) | All `PORTABLE`. Each game is driven by a host test. Sources are pinned by SHA-256 | Play each on a device; record `android_tested`; only then raise to `ANDROID_TESTED` |
| No false `ANDROID_TESTED` claims | IMPLEMENTED_AND_TESTED | The validator rejects `ANDROID_TESTED`/`STORE_READY` without evidence. The app labels games "Preview port" | — |
| Terminal emulator (xterm/VT500, UTF-8, wide chars, 256/truecolor, mouse, alt screen) | IMPLEMENTED_AND_TESTED | 37 unit tests; parse benchmark | — |
| Control profiles (dpad4/8, buttons, gestures, repeat, confirm) + dispatcher | IMPLEMENTED_AND_TESTED (engine) | 8 unit tests; 9 profiles validate against the parser and the JSON Schema | Tune layouts on real screens |
| On-screen controls, keyboard/IME, gamepad (D-pad, hat, stick), mouse taps | IMPLEMENTED_UNTESTED | Compiles; in the APK | Device test with a touch screen and a Bluetooth gamepad |
| App screens: Discover, Search, Details, Library, Create/My Sources, Publish, You, About/Licenses, Diagnostics | IMPLEMENTED_UNTESTED | Debug and release APKs build; manifest components survive shrinking (`check_apk.py`) | First launch on a device; fix whatever the first run shows |
| Original design language (no Material/AndroidX), light/dark/high-contrast, reduced motion | IMPLEMENTED_UNTESTED | Code + design canvas | Device screenshots; TalkBack pass |
| Visual direction approval (owner review gate) | BLOCKED | Design canvas with 11 artboards and a playable prototype was published and shared; no owner feedback received | Owner reviews the canvas and approves or redirects |
| Mascot Kio in launcher icon (adaptive + monochrome) | IMPLEMENTED_UNTESTED | Vector icons in the APK; rendered in the canvas | Check on Pixel and Samsung launchers, including themed icons |
| Brand packs: 6 required + Phosphor (accessibility), Brand Studio, custom brand | IMPLEMENTED_UNTESTED | Compiles; previews in the canvas | Device test of each pack |
| Launcher identity switching (activity-aliases; atomic on API 33+) and pinned shortcut for custom names/logos | IMPLEMENTED_UNTESTED | Manifest verified: 6 aliases, 1 enabled | Test on API 26, 30 and 34+ launchers |
| Catalog: 9 manifests, 189 discovery entries, validator, JSON Schemas | IMPLEMENTED_AND_TESTED | 9 catalog tests; `catalog-cli validate`; the schemas validate all 18 files | Triage the 181 candidates |
| Signed remote catalog sync (ECDSA P-256, version check) | PARTIAL | Signing and verification code exists; signing is wired into `catalog-cli` | Owner creates a catalog signing key and hosting; add key and URL to `config.json` |
| Community server: challenge/PoW, submissions, receipts, dedupe, rate limits, bot PRs, webhook, PKCE exchange, SSRF-safe preview | IMPLEMENTED_AND_TESTED | 15 tests against a fake GitHub (incl. forged/replayed/expired proofs, private-address and redirect SSRF cases) | Deploy (`docs/COMMUNITY.md`) |
| Server deployment, GitHub App, OAuth client | BLOCKED | Needs the owner's GitHub App, OAuth credentials and hosting (account and cost decisions) | Owner creates them; then add `submissions_url` and `github_client_id` to `config.json` |
| My Sources + Suggest to community (anonymous / GitHub credit), status follow-up | IMPLEMENTED_UNTESTED (app) | App side compiles; the request it sends passes the server tests | E2E once the server is deployed |
| GitHub sign-in (PKCE, Keystore-encrypted token, verified credit as co-author) | PARTIAL | Server exchange and verification tested; app flow untested; no client ID yet | Configure OAuth; device test |
| Creator publishing (ownership check, draft validation) | PARTIAL | Draft validation and admin check tested server-side; UI untested. PRs are opened by the bot with co-author credit, not from the creator's fork | Device + live GitHub test |
| Save export/import | IMPLEMENTED_AND_TESTED (core) | Round trip, traversal, foreign archive, zip bomb and oversized-marker tests | Device test of the system file picker |
| Security: no downloaded code, SSRF, path traversal, zip bombs, bounded parsing, no secrets in APK | IMPLEMENTED_AND_TESTED | Backend and runtime tests; `check_apk.py` secret scan | External review before public launch |
| License compliance (texts + exact sources for every bundled binary) | IMPLEMENTED_AND_TESTED | 16 files in `assets/licenses`, checked by `check_apk.py`; in-app viewer | Attach source archives to each release |
| Performance: measured optimizations | IMPLEMENTED_AND_TESTED | Output coalescing cut nInvaders wakeups 339→50/s (−85%); dex −85%; display font −37%; data in `docs/perf/` | — |
| Battery, thermal, frame time on device | NOT_IMPLEMENTED (unmeasured) | Methods documented in `docs/PERFORMANCE.md` | Measure with `dumpsys gfxinfo/batterystats/thermalservice` |
| Shrinking with R8 | BLOCKED | R8 is distributed via Google Maven, which the network policy blocks. ProGuard 7.10 is used instead (dex 3.37 MB → 514 KB) | Switch the jar in `build-logic` where Google Maven is reachable |
| Release keystore + signed, verified APK | IMPLEMENTED_AND_TESTED | Preview key (RSA 4096) made outside Git; v2+v3 verified; cert SHA-256 `d8ea95d3e2e4e6c65983e8a42f613695fd4a668bb42f5b62d06fca2405be0874`; APK SHA-256 `1c89385c8dfd07212cb2102e963182aaa293c243f8a253330f75bf0760e1b0c6` | For public releases, create the key locally (`tools/release/make-keystore.sh`) |
| Release smoke test | PARTIAL | Static checks pass (`check_apk.py`, `zipalign -c`, `apksigner verify`); no runtime test possible here | Install on a device; run the `docs/RELEASE.md` checklist |
| CI (JVM tests, catalog, native build + smoke + runtime tests, debug APK) | IMPLEMENTED_UNTESTED | `.github/workflows/ci.yml`; first runs started on the branch | Check the run results |
| Docs (build, architecture, runtime, catalog, community, privacy, performance, release) | IMPLEMENTED_AND_TESTED | `docs/`; claims were checked against the code, which surfaced and fixed real gaps (backup exclusion, receipt follow-up, gamepad sticks, preview wiring, an unbounded read, over-promising UI text) | — |
| Publishing (Play / F-Droid / GitHub Release) | BLOCKED | Requires the owner's explicit approval (and accounts) | Owner decision |
