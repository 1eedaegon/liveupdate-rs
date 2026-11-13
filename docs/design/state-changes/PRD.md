# State changes PRD

## Problem

On iOS, `liveactivity-rs` reports every lifecycle state of an activity while the application runs:
ActivityKit tells it when an activity is active, stale, ended or dismissed. On Android it reports
`ended`, for its own end, and `dismissed`, from the adapter's event. That an activity became `stale`,
or `active` once Android listed it, is read by `list` only.

Three things are in the way:

- Android tells nobody when a notification's stale time passes. The adapter says `stale` when it is
  asked after that time.
- The core has neither a thread nor a clock, and should keep it so.
- A layer above that wants to report a change must remember the state it reported last, for every
  activity. It loses that with the process, and it sees nothing of a change that another operation's
  reconciliation already stored.

## Goal

The core answers two questions, and the layer above owns the timer:

1. Which states has the application not been told yet? Each one once, durably.
2. When is it worth asking again?

## Required capabilities

- `LiveUpdate::state_changes()` reconciles and answers the activities whose state is another than the
  one it last answered for them: `active`, `stale`, `ended`, `dismissed`. `unknown` is never answered.
- The state last answered is part of the stored receipt, so a new client continues where the last one
  stopped. Receipts of an earlier version are adopted without being answered.
- `LiveUpdate::next_check()` reads the receipts and answers `Soon` while a post awaits Android's
  listing, the earliest stale time of an activity that is active, or `Never`.
- The adapter and the notification never see the state last answered: the AAR does not change.

## Options considered

| Option | Why not |
| --- | --- |
| The layer above compares two `list` results | It needs a durable memory of its own beside the receipts, and misses what another operation reconciled in between |
| An alarm in the AAR queues a `stale` event while the application does not run | An alarm, a receiver and rescheduling at every update, inexact while the device dozes. iOS reports only while the application runs, so this is more than the parity asked for. It can follow |
| A timer thread in the core | It changes what the crate is, and a frozen process runs no timer |

## Success criteria

- Each state of an activity is answered once, in the order it happened to the receipt, also across a
  new client.
- A caller that follows `next_check` asks only when something can have changed: after a post, at a
  stale time, never otherwise.
- Requests, receipts posted to the adapter, and the existing events keep their JSON.
- Tests are written and seen failing first; the host gates stay green.

## Non-goals

- A timer, a thread or a clock in the crate.
- An event for the promotion state. It is read from the record.
- A notification that looks stale, or one that leaves at its stale time.
- Waking an application that does not run.
- Reporting in `liveactivity-rs`: it maps what this crate answers.
