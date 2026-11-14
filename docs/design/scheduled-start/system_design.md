# System design

## The request and the receipt

`startAt` joins the start request, for schema version 2 and the tagged placement. The receipt of such a
start is stored with the state `scheduled`, not awaiting a listing. Its origin keeps `startAt` for the
activity's life, so the same start still answers it after it began.

## `start`

1. Validate, read capabilities, check the permission, reconcile, answer the same key: as before.
2. Without the adapter's `scheduledStart`: `UNSUPPORTED_FEATURE`, nothing stored.
3. Store the receipt as pending, as for a post, and give it to `Driver::schedule` instead of `post`.
   The adapter checks it as it checks a post and refuses what it would refuse at the time. A refusal as
   invalid restores the receipts; another failure leaves the pending receipt for the same start.
4. Confirm and reconcile. A time that had passed was posted by the adapter at once, and the record
   reads `unknown` until Android lists it, as after any post.

## Reconciliation

While a receipt is `scheduled`, reconciliation asks `Driver::starts` before it observes. The adapter
then posts what is due, arms the alarm again, and reports the scheduled receipts it has an account of:

| Report | Meaning | The receipt becomes |
| --- | --- | --- |
| Android lists the notification (no report needed) | It began | `active` or `stale`, as observed |
| `posted` | Posted within the last ten seconds; Android may not list it yet | `unknown`, awaiting the listing |
| `gone` | Posted longer ago and not listed, or the post failed at its time | `dismissed` |
| none | It waits for its time | `scheduled` |

A report for an activity that is not scheduled is ignored.

## `update`, `end`

An update of a scheduled activity is a `CONFLICT`. An end stores `ended` and cancels: the adapter drops
the alarm with the notification. A delayed end keeps no last content, as for an activity the user
removed.

## `state_changes()` and `next_check()`

`scheduled` is a state like the others: answered once, and `active` once after it. `next_check` names
the earliest of the stale times and the start times. A scheduled receipt whose scheduling failed asks
for nothing: it needs its start again.

A host that asks at the start time has the activity begin then, by the adapter's own catching up,
whether or not the alarm has fired.

## The adapter

- **Storage.** The receipts under `records`, read only. Under `starts`, what the adapter did with a
  scheduled receipt: when it posted it, or that it failed. An entry lives while its receipt is
  `scheduled`.
- **Starting what is due.** For each stored `scheduled` receipt whose time has come and that has no
  entry: post it as `post` would, and note the time; a failure is noted instead and not tried again.
  It runs for the alarm, for `schedule`, for `starts`, and for `BOOT_COMPLETED` when the host
  declared that.
- **An unconfirmed receipt.** The lifecycle stores the receipt unconfirmed (`postFailed`), schedules
  it, asks for the starts and only then confirms it. The alarm is therefore set for an unconfirmed
  receipt as for any other: leaving it out would drop the alarm in the very start that set it. Only
  its post waits. One whose time has come is posted by the `schedule` that names it, which also
  forgets an earlier entry of it, and otherwise not until the same start schedules it again: a
  scheduling that failed begins nothing.
- **Posted, then noted.** A process that dies between the two posts the start once more, and one that
  alerts does so twice. The other order could lose a start.
- **The alarm.** One, for the earliest scheduled receipt without an entry. Exact and allowed while
  idle when the application may set exact alarms. Otherwise two inexact ones for the same time: a
  ten-minute window, and one allowed while idle. Whichever fires first posts; the other finds an entry.
- **Cancel.** Removes the notification, the entry and the alarm in one turn with the alarm's own run,
  so a start that is due does not post after its cancel. An alarm that cannot be set again fails no
  cancel.
- **Capabilities.** `scheduledStart` is true. `exactStart` is whether an exact alarm may be set now.

Everything that touches the note or the alarm runs under one lock of the process. A note that
cannot be read is begun anew: it is the adapter's own account, and failing on it would stop every
operation while something is scheduled.

## What is not promised

- The minute, without the exact-alarm permission.
- A start while notifications are off, or one whose post fails for another reason at its time: it is
  reported `gone` and not tried again.
- A start right after the person took the exact-alarm permission away: Android drops the exact alarm
  with it, and the next lifecycle call makes up for it, as after a restart.
- A start after a restart before the application runs, unless the host declared the receiver for it.
- `dismissed` for a start that was removed within ten seconds of its post and before Rust looked: it
  reads `unknown`, as any post that Android never listed.

## Requirements

`docs/spec.md`, "Scheduled start": LU-SCHED-1 onwards.
