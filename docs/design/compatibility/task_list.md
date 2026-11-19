# Task list

- [x] Found: where a version of one part breaks another, by reading both sides of the bridge and the
  public API (PRD). Measured: the device suite of 0.6.0 on Android 8 (system design).
- [x] The owner's choices: all three directions, `#[non_exhaustive]` on the remaining enums too, and
  `minSdk` 26 as it is.
- [x] PRD, architecture, system design and technical design.
- [x] Spec: the section "Compatibility" (LU-COMPAT-1 to LU-COMPAT-6), LU-V2-METRIC-4, and the rows
  whose strictness changes.
- [x] Rust tests, seen failing: `tests/compat.rs` did not compile for the twelve closed enums
  ("unreachable pattern"), the missing constructors and `EventKind::Unknown`; the turned assertions
  of an error's `cause` and an event's `payload` and `url` failed.
- [x] The crate: the tolerant reply types, `EventKind::Unknown`, the open enums, the constructors.
- [x] Kotlin tests, seen failing: five on the JVM, against stubs for the metric text; on the Android 8
  emulator the record with more members (`INVALID_INPUT`), the stored later event (`NATIVE_ERROR`), the
  unknown command and the metric text.
- [x] The AAR: the renderer, the icon resolver, the dispatcher and the event queue without closed
  lists; the metric text and its clock; one rule for a clock's age.
- [x] Found on the way: a snapshot's title and status were validated three times for each
  notification, and are read once now.
- [x] The device tests' own code runs on Android 8.
- [x] `examples/delivery.rs` and the README on the constructors; the README's section "Versions".
- [x] Review, of the Rust and of the Kotlin side: no finding above medium. Taken from the Rust one:
  the docs promise what the attribute gives, a member and a variant that are added, and say which
  objects of a reply may gain members; a test that the status of an envelope decides, and one that no
  `pub enum` in `src` is closed, both enum tests seen failing for one enum closed again. Taken from
  the Kotlin one: `reportedState` is refused in a record that is posted, as the list refused it; a
  decimal is not rounded to three digits; an append to the event queue never stores what it cannot
  read; the listener probe keeps a device's other listeners.
- [x] Found for efficiency, beside the title and the status: every bridge call copied its payload to
  count its bytes, and a report of scheduled starts asked Android once for each note. Both in a
  commit of their own.
- [x] Format, Clippy, host tests, doctests, the mutant of the changed lines (1, caught), spec
  coverage (187 requirements), the CI and release checks; the Kotlin unit tests, lint and the AAR
  checks. Each of the three code commits passes the host tests on its own.
- [x] On devices: Android 8 and the watch ([testing.md](../../testing.md)).
- [ ] The device suite on API 37 is the pull request's CI.
