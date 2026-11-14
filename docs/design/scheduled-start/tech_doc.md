# Technical design

## Rust

- `model`: `StartRequest::start_at: Option<u64>`, left out of the JSON when unset; validated for
  schema version 2, the tagged placement and `MAX_TIMESTAMP`. `ActivityState::Scheduled`.
  `ActivityRecord::start_at`. `NextCheck::At` is the earliest stale or start time.
- `contract`: `PresentationCapabilities::scheduled_start` and `exact_start`.
- `driver`: `Driver::schedule(&DriverRecord)`, by default `UNSUPPORTED_FEATURE`, and
  `Driver::starts() -> Vec<StartReport>`, by default empty. `StartReport { id, outcome }` with
  `StartOutcome::{Posted, Gone}`.
- `bridge`: the commands `schedule` (`{record}`) and `starts` (`{}`, answering an array of
  `{id, outcome}`).
- `client`: `start` branches to `schedule`; `settle` asks `starts` while a receipt is scheduled;
  `reconcile_record` keeps, begins or dismisses a scheduled receipt; `update` and `end` as designed;
  `next_check` takes the start times.

## Kotlin

- `ScheduledStarts`: the note under `starts`, starting what is due, the alarm, the report.
- `LiveUpdateStartReceiver`: declared in the AAR's manifest without a filter and not exported. It
  runs `ScheduledStarts` for the alarm's action and for `BOOT_COMPLETED`.
- `AndroidAdapter`: `schedule`, `starts`, the two capabilities; `cancel` drops the entry and arms.
- `LiveUpdateProtocol`: the two commands.
- `NotificationRenderer`: the state `scheduled` and the origin's `startAt`.

## Tests, written first

Integration tests against the fake driver, which gains `schedule` and `starts`:

1. The request: JSON, schema version 1, the foreground placement, the bound.
2. Without the capability nothing is stored.
3. A scheduled start: stored, scheduled instead of posted, its key, the limit, the same start.
4. A refusal and a failure of the scheduling.
5. Each report, and no report.
6. An update and an end before the time.
7. `state_changes` and `next_check`.
8. The bridge commands and the default driver.

Device tests on the API 37 emulator:

1. A start a few seconds ahead is not shown before and is shown after, through the alarm.
2. A time that has passed is posted at once.
3. What a post would refuse is refused when it is scheduled.
4. A receipt that became due without an alarm begins when `starts` is asked.
5. The reports: posted, gone after the ten seconds, gone for a failed post, none for a listed one.
6. An end before the time leaves nothing.
7. The exact alarm where it is allowed, the inexact ones where it is not.
8. `BOOT_COMPLETED` arms again.
9. From Rust through JNI: `scheduled`, then `active`.

By hand: the receiver in a process that Android started for the broadcast alone.

## Follow-up outside this crate

`liveactivity-rs` maps `scheduled` to its `pending`, reports `active` when the activity begins, and
gives its host the time of `next_check`.
