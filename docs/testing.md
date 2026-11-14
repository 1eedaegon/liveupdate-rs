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
- `ExtensionsInstrumentationTest`: the optional parts of schema version 2 as the adapter builds and keys
  them: links whose intents are sent and received by an activity of the test package, `opened` events,
  a style that changes, the last content of a delayed end, the foreground placement with a service that
  attaches, the alerting channel, the public version and branding.
- `ForegroundServiceInstrumentationTest`: what Android does with a notification that a foreground service
  holds, which the foreground placement of `docs/design/android-core-extensions` rests on. A service and
  a notification listener of the test package show that a posted notification becomes the service's
  without being removed, that a post keeps it and a cancel does nothing, how leaving the foreground
  removes or keeps it, and that a killed process takes it along.

## CI

`.github/workflows/ci.yml` starts with a `changes` job, so a change to docs alone runs only the light
`spec` job. Code changes run the Rust jobs (Linux, the MSRV and the mutation tests), the Android build
(Kotlin unit tests, lint, the AAR and its contents, `cargo ndk` check and Clippy for both ABIs, the
README's example among them), and the emulator suite on an API 37 emulator. The emulator job caches the SDK packages and the system image with its AVD under exact
keys, and leaves a record of each full pass as an artifact (`tests/ci/cache.sh`): the push to main that
merges a pull request finds the pull request's pass for the same inputs and skips the suite. A run by
hand can ignore every cache (`fresh`) or rerun the suite (`retest`).

After a successful `CI` run at the current tip of `main`, `.github/workflows/auto-release.yml` reads the
one-line conventional commit subjects since the latest stable tag. `feat` prepares the next minor;
`fix`, `perf`, `refactor`, `docs`, and `revert` prepare the next patch; `test`, `ci`, `chore`, `build`,
and `style` do not request a release by themselves. Unknown subjects fail the plan. A `!`,
`BREAKING CHANGE:`, or `BREAKING-CHANGE:` never increments major automatically: run **Auto release**
from the Actions page with `major` checked. That creates the major version commit, but the commit must
still pass its own exact-SHA CI before it is tagged.

The generated version commit updates both locks, the README and `CHANGELOG.md`, whose section is made
from one bullet per non-merge commit subject. GitHub does not recursively start workflows for pushes made
with `GITHUB_TOKEN`, and a CI run that the workflow dispatches does not start the workflow again either. So
the run that pushed the version commit dispatches its CI, waits for that run, tags the commit and
dispatches `publish.yml`. The repository's Actions setting must allow
read/write workflow permissions, and a protected `main` must allow this workflow to push its generated
version commit. `bash tests/release/run.sh` tests the policy against isolated temporary Git repositories;
`sh tests/ci/run.sh` checks its triggers, permissions, main-tip guard and dispatch chain.

If the generated version commit was pushed but the run stopped before tagging it, run **Auto release** by
hand without `major`: it tags the untagged version commit, and `publish.yml` still requires that commit's
successful CI. Run `CI` once by hand on `main` first if that run is missing. If a tag exists at `main`'s tip but its GitHub release is missing,
rerun **Auto release** without `major`; its no-change recovery path dispatches `publish.yml` again.

`.github/workflows/publish.yml` requires a successful CI run on main for the tag's exact commit, records
the matching changelog section as the GitHub release notes, and attaches the AAR. After a validated tag
is created, the coordinator dispatches that workflow with `publish=true`; a manual dispatch remains
available for recovery. Publishing still requires the repository variable `CRATES_IO_PUBLISH` to be
`true`, runs only after the AAR is attached, and uses the `crates-io` environment. The crates.io Trusted
Publisher names this repository, `publish.yml`, and that environment, so no other job can mint a token.
The missing-release recovery path uses `publish=false`, which can reconstruct GitHub assets without
attempting to republish an immutable crate version.

## Devices

The emulator suite runs on the newest public Android API, 37 (Android 17), with a Pixel 9 Pro XL screen
and the Google APIs image. It records `ro.build.version.sdk`, `ro.product.model` and
`ro.build.fingerprint` in `device.txt`. API levels below 36 are checked by running the adapter at API 35
on that device; no emulator below API 36 is part of the suite.

API 36 (Android 16) is checked the same way, by running the adapter as API 36 on that device
(LU-V2-PROGRESS-2). That shows what the adapter chooses for API 36, not what API 36 has: a method that only
API 37 has exists on the device either way. Lint does not show it either: with this Android Gradle plugin
its `NewApi` check does not know the members of API 37, and an unguarded call to one passed `lintDebug`.
A call above a device's API level is therefore found only by running on that level. On 2026-11-13 the
instrumentation tests were run by hand on an API 36 emulator (Google APIs image, x86_64):

- The progress test of LU-V2-PROGRESS-2 failed there with `NoSuchMethodError` for `setSemanticStyle`
  before the adapter left that call to API 37, and passed after.
- 55 of 65 tests passed. Of the other ten, eight need API 37: six assert it, one uses a class of it, and
  one expects the Samsung capability that the adapter reports from API 37. One asserts
  `hasPromotableCharacteristics()`, which that image answers with false for every notification, a plain
  ongoing one with a title included. One foreground-service probe failed in the full run and passed three
  times on its own.
- No member newer than API 36 is referenced by the release classes outside the metric style, which runs
  on API 37 only (`javap` over the AAR against the platform's `api-versions.xml`).

On 2026-11-14 the tests of LU-ADAPTER-10 and LU-ADAPTER-11 and the attach test of LU-FGS-6 passed on that
API 36 emulator as on API 37. Both images ignore a post whose time is more than 14 days old and log
"Ignored enqueue for old" for it; no API 35 image was run.

The connected Galaxy S23 (`SM-S911N`) on Android 17 / One UI 9 is also tested explicitly by serial.
It has passed the rich ProgressStyle and MetricStyle reconstruction, Samsung Now Bar extras,
immutable actions, durable action/dismissal events, private images, v1 regressions and Rust/JNI
round-trip tests. The 2026-11-09 final run passed 41 normal tests plus the isolated permission-denied
test without skips; JaCoCo reported 95.36% Kotlin line coverage (658/690). The emulator remains the
reproducible coverage gate; OEM promotion prominence still depends on device settings and system
policy.
