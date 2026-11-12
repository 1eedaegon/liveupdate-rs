# Android core extensions PRD

## Problem

`liveactivity-rs` projects one cross-platform layout onto ActivityKit and onto this crate. Its
roadmap (accessibility metadata, deep links, an explicit Android presentation, relevance and alerts,
typed bindings, a coalescing layout session, later a server transport) was compared with this crate's
contract and adapter. The crate already does what an iOS Live Activity does on a lock screen: it asks
for a promoted ongoing notification and replaces the same notification in place. Most of the roadmap
therefore needs nothing here. Eight behaviors, however, are closed in this crate, and no projection
above it can open them.

## Findings

| Need of the roadmap | Today in this crate | Where |
| --- | --- | --- |
| A tap opens the right screen | The content tap is the launch intent, the same `PendingIntent` for every activity, without the activity's id. Actions are broadcasts only, and Android does not let a notification's receiver start an activity. | `NotificationRenderer.contentIntent`, `action`; LU-ADAPTER-4, LU-V2-ACTION-2 |
| A progress bar beside a countdown | The chronometer is the `timer` style, exclusive with every other style. `liveactivity-rs` drops a layout's timer whenever it has a progress. | `PresentationStyle`; `applyTimer` |
| A layout that changes shape with its values | The template is fixed at start, also in schema 2. | LU-UPDATE-5, LU-V2-MODEL-1; `client::replaces` |
| A final state that stays on screen | `EndPolicy::After` fails. The final snapshot of an end is posted and cancelled at once. | LU-END-3; `client::end` |
| Updates while the app is in the background, and `CallStyle` | No foreground service can hold the notification: it is keyed by a tag, and `startForeground` takes none. | `AndroidAdapter.post`; LU-ADAPTER-1 |
| An update that alerts | Every post alerts only once, on a low-importance channel. | `baseBuilder`; LU-CAP-3 |
| Redacted content on a secure lock screen | `Visibility::Private` hides the content and nothing replaces it: there is no public version. | `baseBuilder` |
| The app's own look | The small icon is `liveupdate_status`; there is no accent color and no sub text. | `baseBuilder` |

## Goal

Open these eight behaviors as optional parts of the schema-2 contract, each behind a capability, so
that `liveactivity-rs` and native Rust applications can use what Android's system templates offer
without this crate learning a layout language. Requests without the new parts keep their meaning and
their bytes.

## Required capabilities

In the order of work:

1. **Links (P0).** A URL on the presentation and on an action, opened only inside the application's
   own package; the opening intent names the activity (and the action); a durable `opened` event for
   hosts that hand the intent back.
2. **Chronometer (P0).** A count up or down beside any style but `timer`.
3. **Style change (P1).** A schema-2 update may carry another system style.
4. **Delayed end (P1).** `EndPolicy::After` keeps the last content, no longer ongoing, until its time.
5. **Foreground placement (P1).** An activity whose notification a host-owned foreground service can
   hold, without a second notification.
6. **Alerting revision (P2).** One revision may alert again.
7. **Public version (P2).** A title and a status for the secure lock screen of a private activity.
8. **Branding (P2).** A small icon resource, an accent color and a sub text.

## Already open: work above this crate

- The explicit Android presentation (`AndroidProgress`, `AndroidMetrics`): segments, points, the three
  track icons, metrics, the critical metric, semantic styles, the BigText summary, action icons and
  authentication, and the chip text are public types. The projection of `liveactivity-rs` reduces them
  to one segment and never builds a metric style.
- The per-style capabilities (`progressStyle`, `metricStyle`, …) are reported; `liveactivity-rs` drops
  them, and needs them to choose a fallback for metrics below API 37.
- Dismissals are events; `liveactivity-rs` does not pass them on.
- A coalescing session fits: revisions may skip numbers (LU-UPDATE-8) and the accepted revision is
  answered again (LU-UPDATE-3).
- Accessibility: system templates have no label per node. A projection folds labels into the title and
  the status. A toggle is an action whose title changes.
- Lock-screen content: a start asks for `private` unless the caller says `public`.
- A server transport: Android has no push that updates a notification. A data message wakes the app,
  which calls `update`; a host that wakes in a service supplies its own `Bridge`.

## Success criteria

- Every extension is specified in `docs/spec.md` with tests at two levels, written and seen failing
  first; the existing rows it changes are changed with their tests.
- The host gates (format, Clippy, tests, 80 % lines, mutants) and the device gates (95 % Kotlin lines,
  90 % of `src/android.rs`) stay green on the API 37 emulator.
- The platform behaviors a design rests on (`tech_doc.md`) are proven on a device before the design's
  contract is added.
- A schema-1 request, a schema-2 request without the new parts, and their stored receipts keep their
  exact JSON.

## Non-goals

- A service, a foreground-service type or a foreground permission in the AAR. The type and its
  permission belong to the application's purpose and to its store review.
- Arbitrary intents, components, URI grants, `RemoteViews` or extras. A link is a URL the application's
  own package opens.
- `CallStyle` in this round. Android posts it only for a foreground service (or with a full-screen
  intent), and it needs a person and answer, decline and hang-up intents. It follows foreground
  placement, with the service started first.
- More than one foreground-placed activity at a time, a relevance order between activities, and a push
  transport.
- Layout, bindings, accessibility metadata or any iOS concern.
