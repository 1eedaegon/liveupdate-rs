# Task list

- [x] PRD, architecture, system design and technical design.
- [x] Spec rows `LU-SCHED`.
- [x] Integration tests against the fake driver, seen failing: first for the missing API, then, with the
  API and no lifecycle behind it, all eight on what they assert.
- [x] The model, the driver, the bridge and the client; the tests pass.
- [x] Device tests, seen failing against a scheduler that does nothing: seven of eight. The eighth, what
  `schedule` refuses, passed already: its checks came with the adapter's new command.
- [x] `ScheduledStarts`, the receiver, the adapter, the protocol and the renderer; the tests pass.
- [x] Found on the way and fixed on its own: the adapter refused a stored receipt for the state the
  lifecycle keeps in it, so a scheduled receipt could not be posted, and neither could a service attach
  to an activity of 0.3 (`fix: a service attaches to an activity the lifecycle stored`).
- [x] By hand: the alarm into a killed process, on the API 37 emulator (`docs/testing.md`).
- [x] README: the feature, the manifest a host adds, the limits.
- [x] Review, of the lifecycle and of the adapter. Taken from it: a scheduling that the lifecycle has
  not confirmed begins nothing; a clock is checked for the age it has at the start's time; a cancel and
  the alarm take turns; a note that cannot be read fails nothing; the alarm is set before the note is
  stored; tests for the paths that were open.
- [x] Format, Clippy, host tests and coverage, the mutants of the changed lines, spec coverage, the
  AAR checks, the device suite.
- [x] Found after the first push, by a test that failed one run in three: with unconfirmed receipts
  left out of the alarm, the `starts` that a start asks before it confirms dropped the alarm again, and
  the activity began only at a later call. The tests had made later calls. They now follow the
  lifecycle's order, with a start and nothing after it; seen failing, then passing.
- [ ] Tell the `liveactivity-rs` session what the crate answers.
