# Task list

- [x] Looked up: what a watch shows for an activity in progress, on Wear OS 5, 6 and 7, and how the
  Ongoing Activity's data is carried (PRD).
- [x] The instrumentation suite on a Galaxy Watch4 Classic, built for `armeabi-v7a`: 63 of 77, 6 of 7
  and 2 of 2 pass; what fails asserts what a newer Android does.
- [x] Tried on the watch: a platform notification with the copied data puts the icon on the watch face.
- [x] PRD, architecture, system design and technical design.
- [x] Spec rows `LU-WEAR`.
- [x] Tests, seen failing: the Rust and Kotlin tests for what was missing, then, against a stub that
  offers nothing, the Kotlin tests and the AAR check on what they assert, and the four device tests on
  the watch.
- [x] The build for `armeabi-v7a`; `WearOngoing`, the adapter, the renderer, the capability; the tests
  pass, the device tests on the watch.
- [x] By hand on the watch: a post through the bridge with the device's own answer, and the watch face.
- [x] README and `docs/testing.md`.
- [x] Review. Taken from it: a library that fails leaves the notification as it is, with a test; the
  library is found by its class in code, not by a string; `WearOngoing` is kept, so that R8 does not move
  its code into the renderer; the README says what passed on the watch and whose statements it repeats.
- [x] Format, Clippy, host tests, the Kotlin unit tests and lint, the AAR checks, spec coverage. The
  device suite on API 37 is CI's: the emulator's image is not on this machine.
- [x] The build after the review on the watch, once it was back on the network: 68 of 82, 6 of 7 and 2
  of 2 pass, the five tests of the Ongoing Activity among them.
