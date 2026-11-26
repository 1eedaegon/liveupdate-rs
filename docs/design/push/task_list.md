# Task list

- [x] PRD from the liveactivity-rs session, with its two corrections.
- [x] Architecture, system and technical design.
- [x] Measure: a dependency's `#[no_mangle] extern "system"` function, never called by the host, is
  exported from the host's cdylib with LTO, on macOS and on Android arm64 (`llvm-nm -D`: `T`). No macro.
- [x] Spec rows (LU-PUSH-1 to 8), then tests first, seen failing: `tests/push.rs` and the outcome's unit test
  did not compile without `push`, `LiveUpdate::push`, `EventKind::Pushed` and the capability; the device
  test did not compile without `LiveUpdatePush`.
- [x] Crate: payload, JNI entry, process-wide lock, event; `examples/push.rs` in the README.
- [x] AAR: `LiveUpdatePush`, event, capability, R8 rules.
- [x] The consumer application: a receiver that plays a messaging service, and `consumer.sh run`'s push
  check.
- [ ] Device: the consumer application by map, with the app swiped away; the S23.
- [ ] FCM with the owner's Firebase project, keys outside the repository.
- [ ] Review, gates, pull request; tell liveactivity-rs on release.
