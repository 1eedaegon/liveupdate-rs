# Task list

- [x] PRD from the liveactivity-rs session's request; names agreed with it (2026-11-27).
- [x] Spec rows LU-PUSH-9 and 10, tests first: `tests/push.rs` did not compile without `outcome`,
  `LAYER_KEY` and the capability; the device test did not compile without `LiveUpdatePush.LAYER_KEY`.
- [x] Crate: public `push::outcome`, `LAYER_KEY`, `push_layer`; the crate's own answer built on it.
- [x] AAR: routing in `LiveUpdatePush.handle`, `nativeLayer`, the capability.
- [x] S23: `PushInstrumentationTest` (3 of 3), with the tests' library playing a layer.
- [ ] Gates, pull request; tell liveactivity-rs on release.
