# AndroidX Test monitor + idling resource (vendored source, test-only)

Robolectric 4.17, which runs Tuikio's UI tests on the JVM (`./gradlew :app:test`), needs
`androidx.test:monitor:1.8.0` and `androidx.test.espresso:espresso-idling-resource:3.7.0` at
runtime. Those binaries are published only on Google's Maven repository, which the environment
this project was bootstrapped in cannot reach, so they are built here from their official source.

- Source: https://github.com/android/android-test, tag `axt_2025_07_30_stable`
  (commit `203cabe1b0f6596226625408f56b845f6bb01ef4`), whose `build_extensions/axt_versions.bzl`
  sets `MONITOR_VERSION = "1.8.0"` and `ESPRESSO_VERSION = "3.7.0"`.
- Copied unchanged: `runner/monitor/java/**` → `src/main/{java,kotlin}` (minus the two
  desugaring compatibility files the upstream build also excludes) and
  `espresso/idling_resource/java/**` → `src/main/java`. As upstream does with Bazel
  `neverlink`, the hidden-API shim `ExposedInstrumentationApi` is compiled against in its
  compile-time form (`hidden/`) and packaged in its runtime form (`shim/`).
- `stubs/java` is ours: minimal stand-ins for the five `androidx.annotation` annotations and
  `androidx.tracing.Trace` the monitor references.
- License: Apache License 2.0 (`LICENSE`, from the same commit).

This module is used only by tests. Nothing from it is packaged into the app.
