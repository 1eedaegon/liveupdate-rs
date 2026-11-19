# Technical design

- `android/build.gradle.kts`: `compileOnly("androidx.wear:wear-ongoing:1.1.0")`, and the same for the
  instrumentation tests, which need it at run time; `armeabi-v7a` among the ABIs of the tests' Rust
  library.
- `android/consumer-rules.pro`: `-dontwarn` for the AndroidX classes that `WearOngoing` names.
- `WearOngoing.kt`: `offersWearOngoing(watch, sdk, library)`, the rule; `hasWearOngoingLibrary()`, the
  class by its name; `elapsedAt(time, wallNow, elapsedNow)`, the clock's time; `WearOngoing.apply(context,
  notification)`, which reads the built notification and writes the data into its extras.
- `AndroidAdapter`: `wearOngoing: Boolean?`, which a test gives and the device answers otherwise; the
  capability; the renderer is told.
- `NotificationRenderer.build`: calls `WearOngoing.apply` on what it built, for an ongoing notification.
- `src/contract.rs`: `PresentationCapabilities::wear_ongoing_activity`.
- `src/presentation.rs`: `AndroidPresentation::wear_ongoing_activity`, true from `AndroidPresentation::new`
  and from JSON without the member, and left out of the JSON when true.
- `NotificationRenderer`: reads `wearOngoingActivity` of a presentation, strictly, and leaves the Ongoing
  Activity out when it is false.
- `flake.nix`: the Rust target `armv7-linux-androideabi`.

## Tests

Written first and seen failing.

1. Rust (`tests/contract.rs`): the capability is read from `wearOngoingActivity` and is false when a
   reply leaves it out.
2. Kotlin (`WearOngoingTest`): the rule for a watch, a phone, API 29, 30, 36 and 37, with and without the
   library, which is asked only on a watch of those versions; the clock's time.
3. Device (`WearOngoingInstrumentationTest`), with the Ongoing Activity asked for by the test, on the
   emulator and on the watch:
   - a posted notification carries one, with the notification's small icon, content intent, title,
     category and status text;
   - a countdown and a clock that counts up become a timer and a stopwatch part at the clock's time;
   - an update replaces it;
   - the last content of an ended activity carries none, and neither does anything when it is not asked
     for;
   - the capability follows.
4. Build (`tests/android/aar.sh`): `WearOngoing` is the only class of the AAR that names AndroidX, and the
   consumer rules have the `-dontwarn` lines.
5. By hand on the watch (`docs/testing.md`): a start through the adapter with the device's own answer,
   and the watch face.
6. The member (LU-WEAR-6). Rust (`tests/extensions.rs`): on from `new` and from JSON without it, left
   out when on, `false` kept both ways, another type refused. Device: with it false a notification
   carries none where one is offered, an update without the member carries one again, and a value that
   is no boolean is refused.
