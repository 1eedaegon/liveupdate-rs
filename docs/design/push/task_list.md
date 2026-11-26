# Task list

- [x] PRD from the liveactivity-rs session, with its two corrections.
- [x] Architecture, system and technical design.
- [x] Measure: a dependency's `#[no_mangle] extern "system"` function, never called by the host, is
  exported from the host's cdylib with LTO, on macOS and on Android arm64 (`llvm-nm -D`: `T`). No macro.
- [ ] Spec rows (LU-PUSH-*), then tests first, seen failing.
- [ ] Crate: payload, JNI entry, process-wide lock, event.
- [ ] AAR: `LiveUpdatePush`, event, capability, R8 rules.
- [ ] Device: the consumer application by map, with the app swiped away; the S23.
- [ ] FCM with the owner's Firebase project, keys outside the repository.
- [ ] Review, gates, pull request; tell liveactivity-rs on release.
