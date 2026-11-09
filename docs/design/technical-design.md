# Technical design

## Packaging

- The `liveupdate` crate, compiled into the application's native library.
- The Android AAR with the adapter, its manifest permissions and its ProGuard rule, attached to each
  GitHub release.

## Contract

Serialized names are camelCase. Request decoders reject unknown members. Response data keeps forward
compatibility: unknown members are ignored and an unknown promotion state reads as `unknown`. Errors
have stable codes and fixed messages; platform causes stay on the device.

## Android policy

- Public Android APIs only, and system templates only.
- Promotion is detected at run time and treated as best effort.
- The minimum SDK (26) is independent of the compile SDK (37).

## Quality gates

- Rust: format, Clippy with warnings denied, unit, integration and doc tests, 80 % line coverage on
  the host, and 90 % line coverage of `src/android.rs` on an emulator.
- Android: lint, unit tests, instrumentation tests, 95 % line coverage.
- Packaging: `cargo package` and the AAR's contents.
- The specification's coverage check.
