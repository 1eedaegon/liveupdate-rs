# Status

## Done

- The Rust lifecycle: start, update, end, list and capabilities, with validation, stable errors,
  durable receipts and reconciliation.
- The JSON bridge and JNI transport; the AAR with schema v1 and rich schema v2 system templates.
- Instrumentation tests on an API 37 emulator for every adapter and JNI path, with Kotlin and Rust
  coverage.
- Rich presentation, action/event, fallback and Rust/JNI tests on a Galaxy S23 running Android 17 /
  One UI 9.
- The extensions of [android-core-extensions](android-core-extensions/PRD.md): links and `opened`
  events, a chronometer beside any style, style changes across updates, a delayed end, the foreground
  placement with `LiveUpdateForeground`, an alerting revision, a public version and branding, each
  behind a capability, with instrumentation tests on the API 37 emulator.

## Not done

- Tauri-side TSX/React projection and JavaScript event routing over the new core event drain.
- Instrumentation for process recreation.
- A check on an Android release below 36 (the adapter's API 35 behavior is tested on API 37).
- A native Rust Android sample, and moving `tauri-plugin-live-activity` onto this crate.
- `CallStyle`, more than one foreground-placed activity, and the follow-ups of
  [android-core-extensions](android-core-extensions/tech_doc.md) in `liveactivity-rs` and the Tauri
  plugin, which name this AAR's classes by its new package.
- The extensions on the Galaxy S23.
