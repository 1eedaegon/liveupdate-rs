# Task list

- [x] PRD, architecture, system design, technical design.
- [x] Spec rows: LU-TARGET-1 to 5, LU-V2-EVENT-8 and 9, LU-JNI-9, LU-FGS-9 and 10.
- [x] Tests first, seen failing: `tests/targets.rs` and `tests/api.rs` did not compile without the target,
  the capability and the `handled` kind; `TargetsTest` and `LiveUpdateEventsTest` did not compile without
  the checks and the listeners; the device tests did not compile without `LiveUpdateActions`,
  `attachOr` and `lifecycleFrom`.
- [x] Crate: `ActionTarget`, `TargetKind`, `NotificationAction::target`, `action_targets`,
  `EventKind::Handled`, `LiveUpdate::android_from_raw`; the lifecycle's check.
- [x] AAR: `Targets.kt`, `LiveUpdateActions.take`, `EventStore.appendHandled`, native listeners,
  `LiveUpdateForeground.attachOr`, the capability.
- [x] `examples/service.rs` in the README, with the Kotlin service.
- [x] The consumer application has a recorder, built from 0.8.0 or this checkout.
- [x] S23: the new device tests (11) and the whole suite (103 and 7); the recorder by hand.
- [x] Review: one high (a second `attachOr` reset the fallback it should cancel), five medium (one fallback
  for all services, the builder under the lock, exported targets, targets in another process, Unicode
  letters that Rust and Java read apart), six low; all taken but the stale service of `update`, which is
  older than this. The tests of the high one and of the refused targets were written before the fix and
  ran after it: 46 device tests on the S23.
- [x] Gates: format, Clippy for the host and Android, host tests, doctests, spec coverage (199
  requirements), CI and release checks, Kotlin unit tests, lint, the AAR checks. Mutants of the changed
  `src` lines: 25 of 28 caught and 2 unviable, the last a timeout on a host at load 300, caught when the
  target's checks ran again (17 of 17); the three excluded mutants are at their lines.
- [ ] CI's API 37 suite.
