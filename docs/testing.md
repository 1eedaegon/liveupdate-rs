# Testing

Every requirement in [the specification](spec.md) names the tests that prove it, and every test names
its requirements in the comment directly above its definition. `sh tools/spec-coverage.sh` checks both
directions and fails on a requirement without a test, a listed test that does not exist or does not
name the requirement, and an ID anywhere else in a test file.

## On the host

```sh
nix develop
cargo test --locked --all-targets     # unit tests in src/, integration tests in tests/
cargo test --locked --doc             # the README's Rust code as a doctest (ReadmeDoctests in src/lib.rs)
cargo ndk -t arm64-v8a -t x86_64 check --locked --features android --all-targets   # with examples/delivery.rs
cargo llvm-cov --locked --summary-only --fail-under-lines 80
cargo +1.90.0 test --locked           # the MSRV, with a Rust 1.90 toolchain
gradle :android:testDebugUnitTest :android:lintDebug :android:assembleRelease --no-daemon
sh tests/android/aar.sh               # what the release AAR holds
sh tools/spec-coverage.sh
sh tests/ci/run.sh                    # CI's change filters, cache keys and record steps
cargo mutants -j 3                    # every mutant of the host code must fail a test
```

The integration tests drive the lifecycle against a fake driver (`tests/common/mod.rs`) that behaves like
the AAR's adapter and can delay a post, keep a cancelled notification listed for a few reads (Android
cancels asynchronously), fail a call or panic.

Host coverage cannot see `src/android.rs`: it compiles for Android only.

## Mutation tests

`cargo mutants` changes the host code one mutant at a time (a condition inverted, a field left out, a
value replaced) and runs the tests; every mutant must fail one. `.cargo/mutants.toml` leaves out
`src/android.rs`, which compiles for Android only and which the emulator suite covers instead. In CI a
pull request tests the mutants of the lines it changes, and a run by hand tests all of them.

## On an emulator

`tests/android/run.sh` runs every instrumentation test on an emulator, with coverage:

```sh
sh tests/android/avd.sh create liveupdate_api37 37.0     # system-images/android-37.0/google_apis
sh tests/android/avd.sh start liveupdate_api37 5580      # headless; waits for the boot
ANDROID_SERIAL=emulator-5580 EXPECTED_SDK=37 sh tests/android/run.sh
sh tests/android/avd.sh stop emulator-5580
sh tests/android/avd.sh delete liveupdate_api37
```

The script refuses a serial that is not an emulator, and every adb call names `ANDROID_SERIAL`, so a
phone on USB is never touched. With `EXPECTED_SDK`, the tests fail on any other API level. It:

1. builds the test APK, whose Rust library (`android/native-test`) uses the crate through JNI and is
   built with `-C instrument-coverage` for the emulator's ABI;
2. runs every instrumentation test with JaCoCo coverage, then, with the notification permission
   revoked, the one class that needs it so (revoking a runtime permission kills the app's process).
   Every test must pass: a failure, an `@Ignore` or a skip by an assumption fails the suite, and so does a
   device test that `docs/spec.md` lists but that did not pass (`tests/android/instrumentation.py`);
3. pulls JaCoCo's data and the Rust profile that the tests write with `__llvm_profile_write_file`;
4. checks the Kotlin line coverage of the unit and instrumentation tests together (95 % in
   `android/build.gradle.kts`) and the Rust coverage of `src/android.rs` on the device (90 % of its lines,
   `RUST_ANDROID_MIN`, and 85 % of its regions, `RUST_ANDROID_REGIONS_MIN`), merging the profile with
   `llvm-profdata` and reporting it with `llvm-cov`;
5. writes `passed.txt` to `$OUT` only after a full pass.

`avd.sh create` writes the AVD's files itself: `android emulator create` takes only a hardware profile
and chooses the system image on its own.

The instrumentation tests:

- `LiveUpdateBridgeInstrumentationTest`: the JSON bridge against the real framework, storage, and the
  merged manifest.
- `AndroidAdapterInstrumentationTest`: the notification each template builds, the content intent,
  visibility, the promotion extra, what observation reports and refuses, the post checks, the storage
  bounds, and the adapter at API 35 on a newer device.
- `NativeRustInstrumentationTest`: Rust calling `LiveUpdate::android()` through JNI on Rust and Java
  threads, many threads and many calls, text and timers across JNI, a dismissal, promotion, a missing
  `ndk-context`, a context without an application context, and a class loader without the AAR.
- `RichPresentationInstrumentationTest`: every v2 system style and fallback, secure icons, immutable
  actions, bounded durable events, Samsung extras, strict malformed-input rejection and Unicode
  scalar boundaries shared with Rust.
- `PermissionInstrumentationTest`: posts without the notification permission.

## CI

`.github/workflows/ci.yml` starts with a `changes` job, so a change to docs alone runs only the light
`spec` job. Code changes run the Rust jobs (Linux, the MSRV and the mutation tests), the Android build
(Kotlin unit tests, lint, the AAR and its contents, `cargo ndk` check and Clippy for both ABIs, the
README's example among them), and the emulator suite on an API 37 emulator. The emulator job caches the SDK packages and the system image with its AVD under exact
keys, and leaves a record of each full pass as an artifact (`tests/ci/cache.sh`): the push to main that
merges a pull request finds the pull request's pass for the same inputs and skips the suite. A run by
hand can ignore every cache (`fresh`) or rerun the suite (`retest`).

`.github/workflows/publish.yml` runs on `v*` tags: it requires a successful CI run on main for the tag's
exact commit, attaches the AAR to the GitHub release, and publishes to crates.io only when run by hand
with `publish` checked and the repository variable `CRATES_IO_PUBLISH` set to `true`, after the AAR is
attached, from the `crates-io` environment. Two settings belong to the repository's owner: the
`crates-io` environment should require a reviewer, and the crates.io Trusted Publisher should name this
repository, `publish.yml` and that environment, so that no other job can mint a token.

## Devices

The emulator suite runs on the newest public Android API, 37 (Android 17), with a Pixel 9 Pro XL screen
and the Google APIs image. It records `ro.build.version.sdk`, `ro.product.model` and
`ro.build.fingerprint` in `device.txt`. API levels below 36 are checked by running the adapter at API 35
on that device; no emulator below API 36 is part of the suite.

The connected Galaxy S23 (`SM-S911N`) on Android 17 / One UI 9 is also tested explicitly by serial.
It has passed the rich ProgressStyle and MetricStyle reconstruction, Samsung Now Bar extras,
immutable actions, durable action/dismissal events, private images, v1 regressions and Rust/JNI
round-trip tests. The 2026-11-09 final run passed 41 normal tests plus the isolated permission-denied
test without skips; JaCoCo reported 95.36% Kotlin line coverage (658/690). The emulator remains the
reproducible coverage gate; OEM promotion prominence still depends on device settings and system
policy.
