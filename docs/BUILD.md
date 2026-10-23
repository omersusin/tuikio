# Building Tuikio

Tuikio builds without the Android Gradle Plugin. A small Gradle plugin in
`build-logic/` (`tuikio.android-app`) drives the SDK's own tools: aapt2, javac/kotlinc,
ProGuard (release), d8 (or dx), zipalign and apksigner. Everything else is plain Kotlin/JVM.

## Requirements

| Tool | Version | Used for |
|---|---|---|
| JDK | 17+ (CI uses 21) | Gradle, Kotlin, tests |
| Python | 3.10+ | `native/build.py`, catalog import, perf tools |
| Zig | 0.17.0 (`pip install ziglang==0.17.0`) | Cross-compiling games and the PTY helper (static musl) |
| Android SDK | platform `android-36`, build-tools 36.x | aapt2, d8, zipalign, apksigner, `android.jar` |
| gcc, make, flex, bison, ncurses headers | any | Host tools that generate game data during the build (bsd-games) |
| qemu-user-static | optional | Running the arm smoke tests on an x86_64 host |

The SDK is found through `sdk.dir` in `local.properties`, or `ANDROID_HOME` /
`ANDROID_SDK_ROOT`. The newest `build-tools/*` directory provides the tools. Individual tools
can be overridden in `local.properties` (`tuikio.aapt2`, `tuikio.zipalign`, `tuikio.apksigner`,
`tuikio.dexer`, `tuikio.frameworkRes`, `tuikio.compileSdk`).

## Build

```sh
python3 native/build.py              # games + PTY helper for arm64-v8a, armeabi-v7a, x86_64
./gradlew :app:assembleDebugApk      # app/build/outputs/apk/debug/tuikio-debug.apk
```

`native/build.py` downloads every third-party source listed in `native/sources.lock.json`
(archives by SHA-256; git sources by commit, archived deterministically and hashed too). It
refuses any file whose SHA-256 differs, unpacks archives with path and link checks, applies
`native/ports/<game>/patches`, and writes `native/build/out/`:

- `<abi>/lib<name>.so`: static-PIE executables, packaged as native libraries (see `docs/RUNTIME.md`),
  plus the PTY helper and `libtuikio_pager.so`, the pager games get as `$PAGER`. Every output is
  checked before it is installed: the right machine for its ABI, position independent and with no
  program interpreter. A makefile that quietly used the host compiler fails the build there.
- `assets/games/...`: game data files installed into a game's home on first launch.
- `manifest.json`: size and SHA-256 per binary. The app checks installed files against it.

It also writes `LICENSES/` in the repository (bundled as `assets/licenses/`): each source tree's
license text and `SOURCES.txt` (exact sources and hashes).

Code layout: `native/tk.py` is the toolkit (sources, Zig wrappers per ABI, helpers);
`native/recipes/` holds the recipes, one module per source family, each registering its games
with `@game(...)`; `native/compat/` is linked into every game (Android fixes: uid/gid calls that
the app sandbox forbids, a missing `/etc/passwd`, stdout buffering; plus `<sys/cdefs.h>` for BSD
code, and `<gettext.h>` and `<termio.h>` for code that expects glibc or System V). A failing
recipe doesn't stop the others; the build reports every failure at the end.

Games are compiled with `-Os -UNDEBUG`: Zig's `cc` defines `NDEBUG` whenever it optimizes, which
other compilers don't, and code that calls functions inside `assert()` would lose those calls.

Useful flags: `--abi x86_64`, `--game 2048`, `--list`, `--jobs N`, `--keep-going`, `--clean`.
Pin a new Ubuntu source with `python3 tools/ports/ubuntu_sources.py --lock <package>` (checked
against the archive's signed index).

The debug APK is signed with a throwaway key that is generated on first use
(`build/debug-signing/`). For release builds see `docs/RELEASE.md`.

## Tests

```sh
./gradlew :core:terminal:test :core:controls:test :core:catalog:test :core:community:test :backend:test
./gradlew :core:runtime:test -PrequireNative   # runs the real x86_64 game binaries
./gradlew :tools:catalog-cli:run --args="validate $PWD/catalog"
python3 native/tests/pty_smoke.py native/build/out/x86_64
python3 native/tests/pty_smoke.py native/build/out/arm64-v8a qemu-aarch64-static
python3 native/tests/pty_smoke.py native/build/out/armeabi-v7a qemu-arm-static
```

Without `-PrequireNative`, the runtime tests skip when `native/build/out` is missing. With it,
a missing binary is a failure (CI uses this).

CI (`.github/workflows/ci.yml`) runs all of the above on every push and pull request, then
builds and verifies a debug APK with the standard SDK. CI holds no signing keys.

## Building in a restricted container (no Google Maven, no SDK download)

The project was bootstrapped in a container that could not reach `dl.google.com`. There,
`tools/container-sdk/setup.sh` assembles a substitute SDK:

- **Compile-time `android.jar`**: synthesized from Robolectric's `android-all` 16 jar (on
  Maven Central) plus the JDK's `java.*` classes (`synthesize_android_jar.py`).
- **Resource linking**: Ubuntu's `aapt2` (build-tools 29) with Ubuntu's `framework-res.apk`
  (API 33). As a result, resources compile against API 33 framework attributes while
  `targetSdkVersion` is 36. The app only uses attributes that exist on API 26+.
- **Dexing**: Ubuntu's `dx`. Kotlin is compiled for Java 8 bytecode with class-based lambdas
  and inline string concatenation (see `build-logic/.../tuikio.kotlin-library.gradle.kts`), so
  dx needs no invokedynamic support.
- **Signing and alignment**: Ubuntu's `apksigner` and `zipalign`.

A machine-local Gradle init script pointed Maven Central at its Google Cloud Storage mirror,
because of rate limits. It is not part of the repository and is not needed elsewhere.

On a normal machine, install the Android SDK and ignore this section. The standard SDK path
(d8, current aapt2, real `android-36` platform) is what CI uses.

## Why no Android Gradle Plugin

- The build had to work where Google Maven was unreachable. The plugin is about 500 lines
  and fully inspectable.
- No AndroidX or Jetpack dependencies are needed: the UI is framework Views with Tuikio's own
  design system, which keeps the APK small and the dependency tree empty.
- Trade-off: no R8 (ProGuard 7 shrinks and optimizes release builds instead), no Android
  Studio project model, and no instrumentation-test runner. See `docs/RELEASE.md` for the
  release checks this replaces.
