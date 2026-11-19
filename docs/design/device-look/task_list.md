# Task list

- [x] Looked at on the S23 and the watch, option by option ([testing.md](../../testing.md)).
- [x] PRD, architecture, system design and technical design.
- [x] Spec rows LU-WEAR-7 and LU-V2-PROGRESS-3; LU-V2-METRIC-4 and LU-WEAR-6 say what changed.
- [x] Tests, seen failing: three in `tests/api.rs`; the decimals on the JVM; on the watch the
  listener heard no removal when the Ongoing Activity was turned off.
- [x] The constructor's default, the decimals, and the cancel before a post without the Ongoing
  Activity.
- [x] The watch face was looked at with the fix, and the watch ran the whole suite with it: 81 of 92
  and 6 of 7, the rest at assertions of a later Android.
- [x] README: what One UI and a watch show.
- [x] Review: no finding of a defect seen, one high about a race. Taken from it: the cancel is
  decided by what the process posted last, and Android's list, which knows a post only later, is
  asked only without that; a list that cannot be read keeps no post back; the test asserts that the
  adapter's cancel queues no event; a decimal that rounds to nothing has no sign. Found by the run on
  the watch after it: the test of a delayed end's last content expected no removal, which a watch now
  has.
- [x] Format, Clippy, host tests, doctests, spec coverage (189 requirements), the CI and release
  checks; the changed `src` lines have no mutant, and the excluded one is at its new line; the Kotlin
  unit tests, lint and the AAR checks.
- [ ] The device suite on API 37 is the pull request's CI.
