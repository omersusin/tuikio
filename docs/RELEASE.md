# Releasing

## Rules

- Signing keys and their passwords never go into Git, app sources, build logs or public CI.
  `.gitignore` excludes `*.p12`, `*.jks`, `*.keystore` and `signing.properties`.
- Passwords are passed to tools as **files** (`file:` / `:file`), never as command-line
  arguments.
- The APK contains no OAuth client secret, GitHub App key or write token.
  `tools/release/check_apk.py` scans for these.
- Publishing (Play, F-Droid, GitHub Releases) needs the owner's explicit go-ahead.

## 1. Create the key (once, on your own machine)

```sh
tools/release/make-keystore.sh ~/tuikio-signing
```

This writes `tuikio-release.p12` (PKCS#12, RSA 4096, valid 30 years) and a random 40-character
password in `keystore-password.txt`, both mode 0600, plus a `signing.properties` for the build,
and prints the certificate's SHA-256.

Back up the keystore and the password **separately**, for example an encrypted backup of the
file plus a password manager entry. Losing the key means installed copies can never be
updated. If it leaks, anyone can ship updates that Android accepts as Tuikio.

For Google Play, enrol in Play App Signing and use this key as the *upload* key. Google then
holds the app signing key, and a lost upload key can be reset.

## 2. Build and sign

```sh
python3 native/build.py
cp ~/tuikio-signing/signing.properties .         # or: export TUIKIO_KEYSTORE=… TUIKIO_KEY_ALIAS=… TUIKIO_KEYSTORE_PASSWORD_FILE=…
./gradlew :app:assembleReleaseApk
```

The release build:
1. compiles;
2. shrinks and optimizes with ProGuard 7.10 (`app/proguard-rules.pro`; the mapping is in
   `app/build/outputs/mapping/release/`; keep it to read crash traces);
3. dexes;
4. packages with fixed timestamps;
5. runs `zipalign -p 4`;
6. signs with APK Signature Schemes v2 and v3 (and writes a v4 `.idsig` for incremental
   installs);
7. runs `apksigner verify`.

The build fails if v2 verification fails. The verify report is written next to the APK
(`tuikio-release-verify.txt`). Without signing settings, the build writes
`tuikio-release-unsigned.apk` instead of guessing.

## 3. Check

```sh
python3 tools/release/check_apk.py app/build/outputs/apk/release/tuikio-release.apk
zipalign -c -p 4 app/build/outputs/apk/release/tuikio-release.apk
apksigner verify --verbose --print-certs app/build/outputs/apk/release/tuikio-release.apk
sha256sum app/build/outputs/apk/release/tuikio-release.apk
```

`check_apk.py` confirms that:
- every manifest component survived shrinking;
- the APK is not debuggable and targets the right SDKs;
- all ABIs ship the same executables, matching their recorded hashes;
- `resources.arsc` is aligned;
- no secrets are embedded;
- the license texts and source listing are present.

Then on at least one arm64 phone (and ideally an x86_64 emulator):
1. Install, then open Settings › Diagnostics › Run the runtime check.
2. Install and play each game for a minute: controls, rotation, background/foreground.
3. Export saves and import them again.
4. Switch the launcher identity in Brand Studio.
5. Record the results in each manifest's `verification.android_tested` (date, device,
   Android version, Diagnostics report) before raising any status to `ANDROID_TESTED`.

## 4. Publish (owner only)

Attach to the release:
- the APK and its SHA-256;
- the certificate SHA-256;
- the ProGuard mapping;
- the source archives from `native/.cache/` (the GPL's corresponding source for the bundled
  games, also listed in the app under About › Licenses).

## Why ProGuard instead of R8

R8 ships through Google's Maven repository, which the bootstrap environment could not reach.
ProGuard 7 (Maven Central) shrinks and optimizes Kotlin well. With the Android-safe
optimization set in `proguard-rules.pro`, it cut dex from 3.37 MB to 514 KB here. Switching
to R8 later is a drop-in change in `build-logic`: same rules file, different jar.
