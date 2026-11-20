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
   built with `-C instrument-coverage` for the emulator's ABI. The test package targets API 36, as a
   host does: a package that names no target has that of `minSdk`, where Android applies none of the
   rules a host is under, and would for one need no permission for an exact alarm;
2. runs every instrumentation test with JaCoCo coverage. The package declares `SCHEDULE_EXACT_ALARM`
   and Android, from 14, allows it no exact alarm, as it allows none to a host until the person does. Then the
   script allows it and runs the scheduled starts once more (taking it back would kill the app's
   process, so that run comes second). Then, with the notification permission revoked, the one class
   that needs it so (revoking a runtime permission kills the process too).
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

## As a host builds it

The suite runs in a test package that has every library, is not shrunk, and calls the adapter from inside.
`tests/android/consumer.sh` builds an application the way the README says a host does, and runs it on one
device:

```sh
ANDROID_SERIAL=<serial> LIVEUPDATE_VERSION=0.7.1 tests/android/consumer.sh build
ANDROID_SERIAL=<serial> tests/android/consumer.sh run
ANDROID_SERIAL=<serial> tests/android/consumer.sh uninstall
```

`run` changes the device as its header says: an install with every runtime permission, the exact alarm
allowed, the application's process ended, and on Android 8.x every background process ended. Its receiver
answers any caller while it is installed: uninstall it afterwards.

- With `LIVEUPDATE_VERSION` the crate comes from crates.io and the AAR from the GitHub release, whose
  sha256 is checked; without it, from this checkout.
- The application (`tests/android/consumer`) adds the AAR as a file with the Kotlin standard library, and
  with `WEAR=1` the library of a watch's Ongoing Activity. It is built with R8 and resource shrinking,
  and its Rust code keeps one client, as the README asks. Its icon is named only in Rust and kept by
  `res/raw/keep.xml`.
- `run` checks the capabilities, a start, an update, a scheduled start that its alarm posts in a process
  that it started after the application's was ended, and an end. A watch build on a watch must offer the
  Ongoing Activity, and then `run` checks that a start's notification carries one and that an update and an
  end at once leave the last content without it (LU-WEAR-7). A check that something is gone fails when adb
  does not answer.
- A scenario at a time: `consumer.sh scenario start`, `update`, `quick-end`, `schedule '{"delayMs":20000}'`,
  `list`, `changes`, `events`, `end`. The application logs every event as soon as it is stored, and every
  opening of its activity, under the tag `LiveUpdateConsumer`.

Run on 2026-11-20 with the published 0.7.1:

- **Galaxy S23** (Android 17, One UI 9): `run` passed. By hand: the card showed the application's own
  icon, as small and as large icon, and the bar filled to its progress; a tap on a button queued an
  `action` event, a button with a link opened the activity with its URL and queued an `opened` event with
  the action, and a tap on the content an `opened` event, each drained by the listener. Built once without
  `keep.xml`, resource shrinking removed the icon and a start failed with `INVALID_INPUT`: the README says
  to keep such icons.
- **Galaxy Watch4 Classic** (Wear OS 5): `run` passed without the watch's library, which the adapter then
  did not offer, and with it, under R8. By hand, the watch face showed the activity's icon and lost it when
  an update and an end came at once. The watch's recent applications listed five applications and not the
  activity.
- **An Android 8 emulator** (API 26): `run` passed. With a PIN and the device locked, a tap on the button
  that asks to unlock first, from the lock screen, reached the adapter and queued nothing ("A live update
  action that requires authentication was not taken: the device is locked."); unlocked, the same tap queued
  the event. Android 8 keeps a cached process from `am kill`: the script then ends the background
  processes with `am kill-all`.
- The S23 also showed, with a throwaway instrumentation test that is not in the repository, a metric style
  as the adapter builds it below API 37: the
  status, a line of the number and the countdown as the header clock, promoted. As the adapter builds it
  on API 37, the system's metric style, promoted as well.

The test packages were removed after each run, the emulator was deleted, and the PIN came off it first.

### Action targets, by hand

Built from this checkout, the consumer application has a recorder (`RecorderService`): a foreground service
that the Record action of a foreground-placed activity names as its target, which calls `attachOr`, takes the
tap and updates the activity through a client that `LiveUpdate::android_from_raw` built from its context.
On the Galaxy S23 on 2026-11-20:

- Unlocked, a tap on Record reached the service, which held the activity's notification (`attachOr`
  answered true), took the tap, and Rust drained it as `handled` and showed Recording.
- Locked (`deviceLocked=1`), a tap on Stop from the shade over the lock screen reached the service the same
  way, without an unlock: the action does not require one.
- With no process of the application, a tap on Record had Android start the process and the service,
  which attached to the stored activity before the tap was taken, and updated it.
- At the end of the activity the service stayed in the foreground with its own notification, the
  activity's gone.

With the published 0.8.0 on 2026-11-20 (`consumer.sh tap TITLE` taps a button of the shade):

- **Galaxy S23**: `run` passed; Record and Discard (which requires the unlock) reached the recorder,
  unlocked, and Rust drained each as `handled`.
- **An Android 8 emulator** with a PIN: `run` passed. Locked, a tap on Discard reached the recorder as
  `withheld=true` and recorded nothing; a tap on Record was taken and recorded; unlocked, Discard was taken.
- **An update of the application without the recorder service**, over its running activity: Android 8
  removed the application's notifications with the update; an update of the activity that still named the
  service failed with `INVALID_INPUT` and left revision 1 stored, and `list` answered it as `unknown`.
- A watch does not list an ongoing notification, so a target's button cannot be tapped there.

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
the matching changelog section as the GitHub release notes, and attaches the AAR. It asks for that run
for two minutes: the coordinator starts it the moment the run ended, and GitHub lists a run as successful
some time after. The release of 0.4.4 asked once, 32 seconds after its CI run had ended, was told of none
and failed with the tag pushed and nothing published; running that failed run again published it. After a validated tag
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
  times on its own: the probe service made itself known after its notification, and a test that saw
  the notification first found no service. It makes itself known first now.
- No member newer than API 36 is referenced by the release classes outside the metric style, which runs
  on API 37 only (`javap` over the AAR against the platform's `api-versions.xml`).

On 2026-11-14 a scheduled start (LU-SCHED-10) was followed by hand on the API 37 emulator, because no
instrumentation outlives its process. A receipt for a time 40 seconds ahead was written into the test
package's preferences, and the receiver was sent its alarm's broadcast once, in a process started for it:
`dumpsys alarm` then listed one `RTC_WAKEUP` alarm for the package at that time. The process was killed.
At the time Android started a new process for the alarm and the notification was listed, ongoing and
promoted. The Rust test library was not mapped in that process.

On 2026-11-14 the tests of LU-ADAPTER-10 and LU-ADAPTER-11 and the attach test of LU-FGS-6 passed on that
API 36 emulator as on API 37. Both images ignore a post whose time is more than 14 days old and log
"Ignored enqueue for old" for it; no API 35 image was run.

On 2026-11-15, after 0.4.0, a scheduled start was followed by hand under a host's conditions: the test
package built to target API 36, on the API 37 emulator unless said otherwise. Each receipt was written
into the package's preferences, the receiver set the alarms in a process of its own, the process was
killed, and the notification was awaited. `dumpsys alarm` gave the alarms.

- **The device tests at that target.** 76 of 77 passed before any change. The one that failed asserted
  that an exact alarm needs no permission, which held for the target the package had until then,
  API 26. The suite targets API 36 since.
- **Without the exact alarm** Android held two alarms: one with a window of ten minutes, and one that is
  allowed while idle with a window of three quarters of the wait, an hour for a start three hours
  ahead or more (read on the API 36 emulator). A start 45 seconds ahead began 32 seconds after its time, and one 80 seconds ahead in
  forced deep idle 60 seconds after: each at the end of the shorter window. One 20 minutes ahead began
  5 minutes 11 seconds after its time on the awake emulator, and in forced deep idle 14 minutes 58
  seconds after, at the end of the only window that counts there. That one was posted promoted.
- **With it** (`cmd appops set … SCHEDULE_EXACT_ALARM allow`) there was an alarm with no window, and the
  start began within a second of its time, awake and in forced deep idle. The API 36 emulator did the
  same: 33 seconds late without it for a start 45 seconds ahead, on time with it.
- **Standby.** Once the emulator was unplugged, the test package, which nobody had opened (standby
  bucket 50), had its inexact alarms put off by a year, and its start in forced deep idle did not begin
  in the ten minutes it was watched, while its exact alarm came on time. While the emulator charged,
  and in the active bucket, the starts began as above. The alarm service lists a quota of wake-ups an
  hour by bucket: 720, 10, 2 and 1.
- **A restart.** With the receiver declared for `BOOT_COMPLETED`, after an orderly restart
  (`svc power reboot`): the receiver ran 84 seconds after the device was up, the 109th to get the
  broadcast. A start that had come due while the device was off began then, 100 seconds after its
  time, and the alarms of a later one were set again: it began in its window. Without the declaration
  no alarm came back and nothing was shown at either time. Both began at the next broadcast to the
  receiver, which stood for the application's next call. Android keeps the restart from a package it
  holds as stopped: after an `adb reboot` a few seconds after a force stop had been ended, it held the
  package as stopped again, the receiver did not run, and Android sent it the broadcast when the
  package was started.
- **An update** of the package (`adb install -r`) kept both alarms, and the start began in its window.
- **A force stop** removed both alarms. Nothing was shown at the time, and the start began at the next
  broadcast to the receiver.
- **The exact alarm taken back** while a start waited (`cmd appops set … SCHEDULE_EXACT_ALARM deny`):
  Android removed the exact alarm and killed the process, and no alarm was left to begin the start.
  The adapter sets an inexact alarm beside the exact one since. On the API 36 emulator that one stayed,
  and the start began 35 seconds after its time in a new process.

The release itself was checked the same day: the AAR of the GitHub release `v0.4.0` has the checksum
published beside it and passes `tests/android/aar.sh`, the sources of `liveupdate` 0.4.0 on crates.io
are those of the tag, and the README's example compiles against it for both Android targets.

On 2026-11-16, after 0.4.1, the exact alarm was followed by hand with the other permission and on two
phones: a Galaxy S23 (`SM-S911N`, Android 17, One UI 9) and a Galaxy Note10 (`SM-N971N`, Android 12,
API 31, One UI 4.1). The test package was built with `USE_EXACT_ALARM` in place of
`SCHEDULE_EXACT_ALARM`, for the Note10 also as it is, and once with both. Nothing was set for it on a
device. A phone has no root: there the package was built once more with the receiver exported, so that
the shell could send it the alarm's broadcast, and the process was killed from the shell, with
`run-as … kill` on the S23 and `am kill` on the Note10. The phones were attached by USB, of which the
Note10 reported power, with the battery saver off, and deep idle was forced
(`dumpsys deviceidle force-idle`). A battery saver and Samsung's sleeping apps were not tried.

- **`USE_EXACT_ALARM` on Android 17.** On the API 37 emulator and the S23 the permission was granted at
  the install, the scheduled-start tests passed as with the exact alarm allowed, 7 of 7 on each, and
  `dumpsys alarm` listed an alarm with no window, allowed by `policy_permission`, beside the inexact one.
  A start into a killed process began 2.3 seconds after its time on the awake emulator and 0.9 seconds
  after in forced deep idle, and on the S23 0.2 and 0.3 seconds after. On the S23 both were listed as
  promoted.
- **Android 12 with `SCHEDULE_EXACT_ALARM`.** On the Note10 the package had the exact alarm without
  anything being set, allowed by `permission`. A start began half a second after its time, awake and in
  forced deep idle, as an ordinary ongoing notification: Android 12 promotes none.
- **Android 12 with `USE_EXACT_ALARM` alone.** The Note10 listed the permission as requested and did not
  grant it. The package had the inexact two alarms. A start 60 seconds ahead began 44 seconds after its
  time, and one 90 seconds ahead in forced deep idle 67 seconds after: each at the end of the window of
  three quarters of the wait. Nobody had opened that package (standby bucket 50, where the one before
  was in bucket 10 after its tests), and Android listed no delay for standby.
- **Both declared**, `SCHEDULE_EXACT_ALARM` with `android:maxSdkVersion="32"`: the API 37 emulator listed
  `USE_EXACT_ALARM` alone, granted, the scheduled-start tests passed 7 of 7, and a start began 2.1
  seconds after its time. The Note10 ran that package a day later, below. No Android 13 was run.
- **The scheduled-start tests on Android 12.** The one that reads the alarms asserts Android 14 and was
  left out. 5 of the other 6 passed. The sixth expects that a clock which is older than 14 days at the
  start's time is refused, which the adapter does from API 35 (LU-ADAPTER-10): of the nine records it
  expects to be refused, the two with such a clock were taken and the other seven refused. What the test
  asserts after them was not run there.

On 2026-11-17, after 0.4.2, the package that declares both, `SCHEDULE_EXACT_ALARM` with
`android:maxSdkVersion="32"`, was run by hand on the Note10, built for a phone as the day before, with
the receiver exported, and its process killed with `am kill`. Nothing was set for it. The phone was
attached by USB and reported power, with the battery saver off, and nobody had opened the package
(standby bucket 50). The scheduled-start tests were not run with it there.

- **What Android 12 grants.** It listed both permissions as requested and granted
  `SCHEDULE_EXACT_ALARM` at the install. `USE_EXACT_ALARM` was not among those granted.
- **The alarm.** `dumpsys alarm` listed an alarm with no window, allowed by `permission`, beside the
  inexact one. A start into a killed process began about half a second after its time in each of three
  runs: one on the awake phone and two in forced deep idle.
- **Deep idle.** After the first run in deep idle the phone's idle history had it leave deep idle at
  about the alarm's time, with the reason `alarm`, so the run was repeated with the state read about
  every two seconds: `IDLE` by 83 seconds before the start's time, and still after the notification was
  posted.

The connected Galaxy S23 (`SM-S911N`) on Android 17 / One UI 9 is also tested explicitly by serial.
It has passed the rich ProgressStyle and MetricStyle reconstruction, Samsung Now Bar extras,
immutable actions, durable action/dismissal events, private images, v1 regressions and Rust/JNI
round-trip tests. The 2026-11-09 final run passed 41 normal tests plus the isolated permission-denied
test without skips; JaCoCo reported 95.36% Kotlin line coverage (658/690). The emulator remains the
reproducible coverage gate; OEM promotion prominence still depends on device settings and system
policy.

On 2026-11-19 the instrumentation tests were run by hand on a watch, a Galaxy Watch4 Classic (`SM-R880`,
Wear OS 5, Android 14, API 34, One UI Watch 6) over wireless debugging. Its userspace is 32-bit, so the
test package was built with `-Pliveupdate.nativeAbis=armeabi-v7a`, which builds the tests' Rust library
for `armv7-linux-androideabi`; the device script takes emulators only.

- **The suite as it was.** 63 of 77 tests passed in the main run, the Rust and JNI ones among them, 6 of
  7 with the exact alarm allowed, and both without the notification permission. Each of the 15 that
  failed asserts what a newer Android does: four the refusal of an old clock (API 35), one of them
  again in the second run; three use `hasPromotableCharacteristics` or the class of the progress style
  (API 36); six assert API 37, and one expects the Samsung capability of API 37.
- **The Ongoing Activity** (LU-WEAR-1 to LU-WEAR-4). With it in the build the main run has 82 tests,
  and on the watch the adapter offers it for every notification of every test: 68 passed, the five of
  the Ongoing Activity among them, and the same 14 failed; the other two runs were 6 of 7 and 2 of 2
  again. The watch's own answer for the capability is true.
- **The watch face, by hand.** A plain ongoing notification left the watch face as it was. A post
  through the bridge, with the device's own answer, listed the notification with
  `android.wearable.ongoingactivities.EXTENSIONS` in its extras and put the activity's icon at the bottom
  of the watch face. The recent applications were not looked at.

The test package was uninstalled after each of the day's runs but one: the watch left the network with it
installed, and it was removed when the watch was back.

On 2026-11-19 the instrumentation tests were also run by hand on an emulator of Android 8 (API 26, the
Google APIs image `OSR1.180418.026`, x86_64), the lowest version the AAR runs on
([compatibility](design/compatibility/system_design.md)).

- **The suite of 0.6.0 as it was.** The main run ended at
  `anAppWithoutALaunchActivityGetsNoContentIntent`: its context names a package that is not installed,
  and Android 8 ends the process of an application whose notification has an icon it cannot load.
  Without that test 83 ran and 57 passed. Of the 26 that failed, 10 assert what a later Android does, 7
  call a method of a later Android in the test's own code (`PendingIntent.isImmutable`,
  `Notification.Builder.getStyle`), and 9 need the notification listener, which the tests allowed with
  a shell command that Android 8 does not have.
- **With the tests' own code made to run there**, and the tests of the compatibility change: 79 of 91
  in the main run, and 5 of 7 with the exact alarm allowed. Each of the 12 that fail stops at an
  assertion of a later Android: one of API 34, two of API 35, two of API 36 and seven of API 37; the
  two of the second run are among them. None fails for what the adapter does on Android 8.
- **A run that is not counted.** While another project's build loaded the host, with a load of more
  than a hundred on twelve processors, the tests of the foreground service timed out waiting for a
  notification to go, and every test after them for the one that was left. The same package passed
  as above once the host was quiet. A device run needs a host that is not busy.
- **Not run:** the run without the notification permission, which is of API 33.
- Found on the way: on Android 8 a shell command of a test that writes to a pipe the test has closed
  ends the whole run, so the tests read a command's output to its end.

The emulator was this run's own (`tests/android/avd.sh create liveupdate-api26 26`), and the test
package was uninstalled after each run.

The same day the watch ran the suite again, with the compatibility change before its review: 79 of 90 in
the main run and 6 of 7 with the exact alarm allowed. Each of the 11 that fail stops at an assertion of
a later Android: two of API 35, two of API 36 and seven of API 37. It was not run again after the
review.

On 2026-11-19 the notifications of 0.7.0 were also looked at by hand, option by option, on the Galaxy
S23 (Android 17, One UI 9) and the watch ([device-look](design/device-look/PRD.md)). A throwaway
instrumentation class posted scenes through the bridge on command, and the screen was captured over
adb; it is not in the repository.

- **Looked at on the S23:** a progress style at 0, 1, 25, 75, 99 and 100 of 100 with segments, points
  and icons, and without them; the longest title, status, text and button titles; zero to three
  buttons; every option of a presentation at once; a timer, a chronometer beside a style, and metrics
  of every kind; the status chip; the banner of a revision that alerts; an activity that is not
  promoted, folded and unfolded; five activities at once; landscape; the lock screen with each
  visibility and a public version; the Now Bar with and without `samsungNowBar`.
- **Right as it is:** the tracker is at the track's left end at 0 and at its right end at 100 and never
  leaves it; long texts end in an ellipsis inside the card; the banner and the card keep their shape
  in landscape; a locked screen shows a public activity in full, a private one by the application's
  name, a public version in its place, and a secret one not at all.
- **Found and fixed** (LU-WEAR-7, LU-V2-PROGRESS-3, LU-V2-METRIC-4): the watch kept the icon of an
  Ongoing Activity that an update had turned off; `ProgressStyle::new` built a bar that looked the same
  at 0 and at 100; a decimal metric showed every digit below API 37 and two on it.
- **What One UI does**, now in the README: with `samsungNowBar` the Now Bar and the card show two
  lines, the application's icon and no progress bar, while a promoted activity without it is in the
  Now Bar with its bar; a point at the track's full length is not drawn; the chip shows the first
  metric and not the critical one; long values of three metrics are cut; the header clock stands
  against the title without a space; a summary is not shown beside a sub text.
- **A right-to-left layout** follows the system and no per-application language. It was forced as the
  developer option does, with the language left Korean: the property `debug.force_rtl`, and then the
  configuration update that the system's language picker makes, from the instrumentation. The card is
  mirrored when it is first shown and when its style changes. After an update of the same style One
  UI shifts the status and the progress bar onto the icon, and the banner is not mirrored. On
  2026-11-20 the system's language was Arabic (`ar-EG`, set as the language picker sets it, then set
  back to Korean) with the published 0.8.0's consumer: the same, the card mirrored when first shown and
  the status and the bar over the icon after an update of the same style.
- **On the watch:** an activity is the icon on the watch face. An ongoing notification is not among
  the watch's notifications, and a revision that alerts does not pop up. With the fix the icon goes
  when an update turns the Ongoing Activity off and when the activity ends with a last content. The
  watch then ran the whole suite with the fix: 81 of 92 in the main run and 6 of 7 with the exact
  alarm allowed, the 11 that fail each at an assertion of API 35, 36 or 37 as before.

The phone's rotation and layout direction were put back, and the test package was removed from both
devices. The phone was locked for the lock screen and unlocked again by its owner's word.
