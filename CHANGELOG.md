# Changelog

## Unreleased

## 0.9.1 - 2026-11-26

- docs: a phone's activity on a paired watch, as Galaxy Wearable's bridge treats it (`e797352`)

## 0.9.0 - 2026-11-26

- feat: an activity kept off a paired watch, the settings of promotion, and channels named by the application (`a7361bb`)
- test: the consumer application names its channels and asks for the settings of promotion (`f12e532`)
- docs: the settings, the channels' names, and what is not offered, with CallStyle's evidence (`ecaa9d4`)
- ci: a pull request reads the commit subjects alone, also while a release waits for its tag (`92cada1`)

## 0.8.3 - 2026-11-26

- docs: what the crate is, said where people search for it (`e4b0f6b`)

## 0.8.2 - 2026-11-20

- ci: the emulator job runs an application built as a host builds one, and taps the recorder (`804b27f`)
- perf: read passes the stored receipts on as they are, counted without parsing them (`be816f3`)
- ci: the consumer's run checks the recorder wherever the build has one (`c8ce021`)
- docs: main's suite at 0.8.1 on an Android 8 and an API 36 emulator (`5f51177`)

## 0.8.1 - 2026-11-20

- ci: a pull request finds a moved mutant exclusion and a refused commit subject (`b226de8`)
- perf: a target's tap reads the stored receipts once (`fd1e475`)
- test: a service whose activity another service took shows its own, and each keeps its own (`156c84a`)
- test: the consumer's recorder has a button that asks for the unlock, and the script taps buttons (`704aeee`)
- docs: what the published 0.8.0 did on a locked Android 8, and after an update of the app (`9320915`)
- docs: a language written from the right shows the card as the forced layout did (`a9c507f`)

## 0.8.0 - 2026-11-20

- test: an application built as a host builds one, from the published crate and AAR (`b820b16`)
- docs: an icon that only a presentation names is kept from resource shrinking (`ba02041`)
- feat: an action can deliver its tap to a service or a receiver of the application (`a97f5e8`)
- feat: a client built from a context, without ndk-context (`b0a33a3`)
- feat: a foreground service that attaches with a fallback is never without a notification (`79c1077`)
- test: the consumer application has a recorder that an action's target reaches (`d3b8535`)
- docs: a recorder's button that runs its own service, and the service's own Rust (`32e80bd`)
- test: the target tests pace their post, and the native test library is formatted (`c55365e`)
- test: the fallback test waits for the listener to hear the removal (`958bde8`)

## 0.7.1 - 2026-11-19

- fix: a watch's icon goes when an update turns the Ongoing Activity off, and with the end (`135ccd7`)
- fix: a progress style built by its constructor shows its progress (`bc3fc9b`)
- fix: the metric text below Android 17 rounds a decimal as Android 17 does (`2848906`)
- docs: what One UI and a watch show of the options (`e9e7e4e`)

## 0.7.0 - 2026-11-19

- feat: the AAR leaves alone what it does not know, and shows metrics before Android 17 (`e7c7d37`)
- feat: the crate reads what it knows of a later AAR's reply, and an event of an unknown kind (`959520c`)
- feat: every public enum may gain a variant, and what a caller builds has a constructor (`33b8095`)
- docs: which versions work together, and what each Android version shows (`d5fc373`)
- perf: a bridge call no longer copies its payload to measure it (`4507504`)
- test: the last commit ran on the Android 8 emulator (`603acb5`)

## 0.6.0 - 2026-11-19

- feat: a presentation can do without the watch's Ongoing Activity (`3c05ca4`)

## 0.5.1 - 2026-11-19

- docs: the README says how to use a watch, and names its library where an app installs (`675506e`)
- docs: the watch's caveat names the versions it is for (`c26dd0c`)
- docs: the watch's caveat says what older watches do (`cfaafd4`)

## 0.5.0 - 2026-11-18

- feat: a live activity on a watch carries an Ongoing Activity (`8181411`)
- test: the Ongoing Activity's last build ran on the watch (`91757df`)
- ci: the release waits for its CI run to be listed (`81ebc21`)

## 0.4.4 - 2026-11-18

- fix: an action that requires authentication is not taken from a locked device below Android 12 (`9069606`)

## 0.4.3 - 2026-11-18

- test: the Note10 ran the package that declares both exact-alarm permissions (`645baa5`)
- fix: large receipts make room in the store instead of failing every start (`8f1a2c8`)
- ci: the mutation tests may take 90 minutes (`a6a6884`)
- docs: an action asks for authentication from Android 12 (`859a27f`)

## 0.4.2 - 2026-11-16

- chore: Clippy denies unwrap and expect outside tests (`d4abdfa`)
- docs: Android 12 needs SCHEDULE_EXACT_ALARM beside USE_EXACT_ALARM (`bc096cd`)

## 0.4.1 - 2026-11-15

- fix: an inexact alarm stays beside the exact one of a scheduled start (`7009f68`)

## 0.4.0 - 2026-11-14

- feat: a start that begins at its time while the application does not run (`8ca4486`)

## 0.3.3 - 2026-11-14

- fix: a clock Android would ignore is refused (`20d63ab`)
- fix: a service attaches to an activity the lifecycle stored (`b535b51`)
- test: the probe service is known before its notification (`1ce3968`)

## 0.3.2 - 2026-11-13

- fix: a schema-2 progress is posted on Android 16 (`e483690`)

## 0.3.1 - 2026-11-13

- fix: a stopwatch or countdown metric is written in the names the adapter reads (`7632cb4`)

## 0.3.0 - 2026-11-13

- feat: each state of an activity answered once, and when to ask again (`43ffa2f`)
- refactor: take back the Android namespace io.github.eedaegon.liveupdate (`34d9d9a`)
- test: the excluded mutant of the default event drain is on its new line (`be250b0`)

## 0.2.1 - 2026-11-13

- ci: the release run waits for its version commit's CI and tags it (`091f3cc`)
- fix: a post the adapter refuses as invalid leaves no receipt (`14a7713`)

## 0.2.0 - 2026-11-12

- ci: publish validated tags to crates.io (`b1b504a`)
- refactor: name the Android namespace io.github.gon.liveupdate (`d94caf3`)
- feat: links, a delayed end, a foreground placement and more for schema 2 (`2d9b636`)

## 0.1.3 - 2026-11-10

- docs: use rich schema in the primary example (0.1.3)
- ci: automate conventional non-major releases
- fix: configure automatic tag identity

## 0.1.2 - 2026-11-10

- Added `LiveUpdateEvents.setListener` to the AAR: the action and dismiss receivers call the app's one listener once
  each event is stored, so the app drains `LiveUpdate::events()` at once instead of polling. A listener that throws is
  logged and the event stays queued.

## 0.1.1 - 2026-11-10

- Breaking pre-1.0 cleanup: removed the redundant `Backend` enum and `ActivityRecord.backend` field.
  This crate is an Android core; cross-platform adapters add their own backend discriminator at
  their public boundary.

## 0.1.0 - 2026-11-09

- Added schema v2 Android presentations for Standard, BigText, ProgressStyle, Timer and MetricStyle,
  with strict validation and lower-API fallbacks while retaining schema v1 JSON compatibility.
- Added safe resource/app-private icons, immutable notification actions, a bounded durable
  action/dismissal event queue, `LiveUpdate::events()`, detailed presentation capabilities and an
  explicit Samsung Now Bar mapping.
- Added Rust contract/integration tests and API 37 instrumentation coverage, including a connected
  Galaxy S23 run through Rust, JNI and the Android framework.

## 0.1.0-alpha.2

Fixes found while writing the specification and its tests:

- Ending an activity while 128 tombstones were kept evicted the record it had just ended, so a second
  `end` answered `NOT_FOUND`. The oldest tombstone now goes first.
- A cancelled notification that Android still listed (its cancel is asynchronous) made the 32nd live
  activity fail with `NATIVE_ERROR`. Observations are now bounded at 160, as the adapter bounds them.
- A driver that panicked left the client's lock poisoned, and every later call answered
  `NATIVE_ERROR`. The client now takes the lock over.
- `LiveUpdate::android()` panicked when `ndk-context` was not initialized yet, and that panic made
  every later call fail. It now returns `NATIVE_ERROR`, and works once the host has initialized
  `ndk-context`.

Tests, CI and docs:

- `docs/spec.md`: every requirement with an ID and the tests that prove it, checked by
  `tools/spec-coverage.sh`.
- Unit tests in the modules, more integration tests, Kotlin unit tests, and instrumentation tests for
  every adapter and JNI path, run with Kotlin and Rust coverage on an API 37 emulator by
  `tests/android/run.sh`.
- CI runs only what a change reaches, caches the toolchains, the SDK packages and the system image,
  and reuses a pull request's emulator pass on main. CI's emulator ran API 36 until now: `android
  emulator create` picks its own system image. It now runs the API 37 image it installs.
- A release workflow for `v*` tags: main's CI must have passed for the tag's commit; the AAR is
  attached to the GitHub release; crates.io Trusted Publishing runs only when asked for.
- The README installs the crate from crates.io and the AAR from the GitHub release, and its Rust code
  compiles as a doctest.
- The planning documents moved from `docs/implementation` to `docs/design`.
- The README's code is `examples/delivery.rs`, which CI compiles for Android. The README now says to add
  `kotlin-stdlib`: a file dependency on the AAR does not bring it, and an app without Kotlin fails with
  `NoClassDefFoundError` at the first call.
- Mutation tests: every mutant of the host code fails a test (`cargo mutants`, `.cargo/mutants.toml`).
  Pull requests test the mutants of the lines they change.
- `update` no longer checks for a record that is `unknown` without awaiting a post: the lifecycle
  never stores one.
- Publishing to crates.io waits for the AAR job and runs in the `crates-io` environment.

## 0.1.0-alpha.1

The first alpha on crates.io: the Rust lifecycle (`start`, `update`, `end`, `list`, `capabilities`)
with idempotent keys, ordered revisions, durable receipts and reconciliation with Android's active
notifications; the JSON bridge and its JNI transport; and the Android AAR with the progress and
countdown templates and promoted ongoing notifications on API 36 and newer.
