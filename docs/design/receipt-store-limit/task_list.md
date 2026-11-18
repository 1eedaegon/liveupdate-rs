# Task list

- [x] Found and measured: a throwaway program over the JSON bridge, the crate of 0.4.2 (PRD).
- [x] PRD, architecture, system design and technical design.
- [x] Spec row LU-LIMIT-5.
- [x] Tests, seen failing: first for the missing rule, then, against a stub that kept the old behavior,
  each on what it asserts. Over the JSON bridge a start failed with the adapter's `INVALID_INPUT`.
- [x] `STORE_LIMIT`, `receipt_bytes`, `fitting`; the tests pass.
- [x] Found on the way: measuring at every store made the host tests seven times slower in a debug
  build. The rule moved to where content grows, a start and an update
  ([system_design.md](system_design.md)).
- [x] README: the limit beside the 32 and the 128.
- [x] Review. Taken from it: a start that the adapter refuses puts back the tombstones it would have
  let go, with a test; what goes with a tombstone, and the same start again near the limit, are stated
  in the system design; a metric's value is a decimal number too.
- [x] Format, Clippy, host tests, the mutants of the changed lines (30: 28 caught, 2 that do not
  compile), spec coverage.
