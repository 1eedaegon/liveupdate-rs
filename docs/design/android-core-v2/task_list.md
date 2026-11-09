# Task list

## Contract and test-first core

- [x] Add spec IDs for v2 model, styles, actions/events, icons, vendor extensions, capabilities, and
  compatibility.
- [x] Write Rust serialization/validation tests and confirm RED.
- [x] Write lifecycle/bridge event and v2 replacement tests and confirm RED.
- [x] Implement cohesive presentation/event models and v1-compatible decoding.
- [x] Implement driver/bridge/client event draining and detailed capabilities.

## Android adapter

- [x] Write Kotlin parser/projection tests and confirm RED.
- [x] Write instrumentation tests for all five styles, API fallbacks, actions/dismissals, icons,
  capabilities, Samsung extras, and invalid input; confirm RED on the S23 where possible.
- [x] Split rendering, icons, event storage/receivers, and vendor mapping into focused files.
- [x] Add non-exported receivers and bounded event queue.
- [x] Implement ProgressStyle (API 36), MetricStyle (API 37), and lower-API fallbacks.
- [x] Implement secure resource/app-private image resolution and immutable unique actions.

## Verification and integration

- [x] Run format, Clippy, Rust unit/integration/doc tests, Gradle unit/lint/build, AAR checks, spec
  mapping, and coverage thresholds.
- [x] Run the complete instrumentation suite on `SM-S911N` by explicit serial; retain artifacts.
- [x] Iterate RED/GREEN on every S23-specific failure and rerun affected plus full suites.
- [ ] Compile Tauri TSX layout projection to v2 core requests and route core events to Tauri.
- [ ] Remove duplicated plugin Android lifecycle/rendering only after parity tests are green.
- [x] Perform inline security, Rust, Kotlin, architecture, and maintainability review; fix all
  critical/high findings and practical medium findings.
- [ ] Review the TypeScript compiler/event routing together with the pending Tauri migration.

## Done definition

- [x] No ignored/skipped required tests.
- [x] Host line coverage >= 80% and repository Android thresholds pass.
- [x] Every new spec promise maps to at least one test, with critical behavior covered at two levels.
- [x] The S23 proves start/update/action/dismiss/end and leaves no test notification or queued event.
- [x] Existing user worktree changes remain present and unrelated files are untouched.
