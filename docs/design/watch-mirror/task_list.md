# Task list

- [x] The bridge measured on the S23 and the Galaxy Watch4 (PRD).
- [x] PRD, architecture, system and technical design.
- [x] Measure: a HIGH mirror kept quiet on the phone by its group (`setSilent`'s way) is muted on the watch too
  (2026-11-27). The mirror alerts both, and the activity's own notification stays quiet then.
- [x] Spec rows (LU-MIRROR-1 to 6), then tests first, seen failing: `tests/mirror.rs` did not compile without
  the member and the capability.
- [x] Crate and AAR.
- [x] On the S23: `MirrorInstrumentationTest` (4 of 4), and `consumer.sh run` with its mirror check.
- [ ] The consumer application's mirror on the watch, worn.
- [ ] Review, gates, pull request.
