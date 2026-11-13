# Task list

## Groundwork

- [x] Compare the roadmap of `liveactivity-rs` with this crate; write the PRD, architecture, system
  design and technical design.
- [x] Prove on a device what the foreground placement rests on
  (`ForegroundServiceInstrumentationTest`).
- [x] Rename the Android namespace and every identifier under it to `io.github.gon.liveupdate` (0.2.0
  and 0.2.1). Taken back in 0.3.0: `io.github.eedaegon.liveupdate` for Java names, as in 0.1, and
  `io.github.1eedaegon.liveupdate` for plain strings.

## Links (P0)

- [x] Spec rows `LU-V2-LINK`; change LU-ADAPTER-4 and LU-V2-ACTION-2.
- [x] Rust: `open_url` on the presentation and on an action, URL validation, `EventKind::Opened`.
- [x] AAR: `LinkResolver`, package-bound intents distinct per activity, the activity and action ids in
  the intent, an unhandled URL fails the post; `LiveUpdateEvents.opened`.
- [x] Device tests: the built intents, an opening action queues no event, `opened` is drained.

## Chronometer (P0)

- [x] Spec rows `LU-V2-CHRONO`.
- [x] Rust: `chronometer`, refused beside the `timer` style; tests first.
- [x] AAR: the header clock beside every other style; device tests per style.
- [ ] Device check: the chip without a chip text (the system UI's; not asserted).

## Style change (P1)

- [x] Change LU-UPDATE-5 and LU-V2-MODEL-1; add the `LU-V2-MODEL` row.
- [x] Rust: a schema-2 update may change the style; the record's template follows its snapshot;
  observation and stored-record checks compare with the snapshot.
- [x] AAR: the renderer takes the style from the snapshot.

## Delayed end (P1)

- [x] Device proof: a final post replaces in place and times out.
- [x] Change LU-END-3 and LU-LIST-7; add `LU-END` rows; change the README's sentence and LU-DOCS-2.
- [x] Rust: `dismiss_at`, post instead of cancel; reconciliation unchanged.
- [x] AAR: the final form; a passed time cancels.

## Foreground placement (P1)

- [x] Spec rows `LU-FGS`; change LU-ADAPTER-1, LU-ADAPTER-6 and LU-LIMIT-1.
- [x] Rust: `Placement`, one live foreground activity, conflict under the same key.
- [x] AAR: the key by placement in post, observe and cancel; `LiveUpdateForeground`; immediate display;
  leaving the foreground before a cancel; the listener.
- [x] Device tests with the host service of the test package: attach, update, end, detach, and an
  attach that shows a stored activity again.
- [x] `attach` answers false without a live activity; the README says to start the activity first.

## Alert, public version, branding (P2)

- [x] Device proof: a post on another channel replaces in place.
- [ ] The public version of a promoted notification on a lock screen (the system UI's; not asserted).
- [x] Spec rows `LU-V2-ALERT`, `LU-V2-PUBLIC`, `LU-V2-BRAND`; change LU-CAP-3.
- [x] Rust members and validation; AAR builder calls and the second channel.

## Every extension

The chronometer was built test first. The others were implemented first and tested after, at the
owner's request; their tests were not seen failing.

- [x] Its capability, false from an adapter that does not report it (`LU-V2-CAP`).
- [x] Its usage in the README, feature by feature.
- [x] Format, Clippy, host tests and coverage, Kotlin unit tests and lint, the AAR check, the spec
  coverage check and the device suite on the API 37 emulator.
- [x] The mutation tests of the changed lines.
- [ ] The suite on the Galaxy S23.

## Later

- [ ] `CallStyle`, on a foreground placement whose service starts first.
- [ ] More than one foreground-placed activity.
- [ ] A relevance order between activities.

## Done definition

- [x] No ignored or skipped required test.
- [x] Host line coverage at least 80 %; Kotlin line coverage at least 95 %.
- [x] Every new promise maps to a test, the lifecycle ones at two levels.
- [x] Schema-1 requests, schema-2 requests without the new members and their receipts keep their JSON.
