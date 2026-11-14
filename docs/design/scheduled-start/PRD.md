# Scheduled start PRD

## Problem

A live update is posted when the application asks for it. An application that knows now that
something begins later, a ride at nine or a match at eight, must run at that moment to show it.
Android has no push that posts a notification, and an application that does not run posts nothing.

iOS 26 starts an activity at a time the application named earlier. `liveactivity-rs` has that in its
contract and answers `UNSUPPORTED_FEATURE` on Android, because nothing below it can post later.

Only code that Android starts without the application can post at a time: an alarm and a receiver in
the AAR. The Rust lifecycle does not run then. The adapter already builds a notification from a stored
receipt without Rust, for a foreground service that attaches.

Android's guidance for Live Updates allows it: "it may be appropriate to automatically show a Live
Update when the scheduled event begins", for an activity that is imminent.

## Goal

A start that names a time. The activity is stored at once and begins at that time, also while the
application does not run. Until then the application sees it as `scheduled`, can end it, and is told
once when it begins.

## Required capabilities

- `StartRequest::start_at`: the time to begin at, in Unix milliseconds. A time that has passed begins
  at once.
- A state `scheduled`, in `list`, in every returned record and in `state_changes()`, which answers it
  once and `active` once after it. The record names its start time.
- A scheduled activity holds its key and counts for the limit. The same start answers it; an update is
  a `CONFLICT`; an end leaves nothing on screen, whatever its policy.
- `next_check()` names the start time beside the stale times, so an application that runs asks then
  and has the activity begin on time whatever the alarm does.
- The adapter checks a scheduled start as it checks a post, when it is scheduled: what it would refuse
  at the time is refused at once.
- At the time the AAR posts the stored receipt, alerting when its content alerts.
- An alarm that was lost, after a restart or a force stop, is made up for at the application's next
  lifecycle call: the activity begins then. An application may opt in to arming again after a restart.
- What the application was not there for is still told truly: an activity that began and was removed
  before the application ran again is `dismissed`, and so is one the adapter could not post.
- Capabilities say whether a scheduled start is offered, and whether its alarm is exact.

## Decisions

| Question | Decision | Why |
| --- | --- | --- |
| Exact alarm | Used when the application may set one (`canScheduleExactAlarms`). The AAR declares no alarm permission | `SCHEDULE_EXACT_ALARM` is denied by default from Android 14 and `USE_EXACT_ALARM` is for alarm and calendar applications. A library that declared either would decide it for every host |
| Without it | Two inexact alarms for the same time: one in a ten-minute window, one that may fire while the device dozes | Either alone is weaker: the window waits out a doze, the other may be an hour late on an awake device |
| Restart | Alarms are lost. The receiver arms again when the host declares it for `BOOT_COMPLETED`; without that the next lifecycle call makes up for it | `RECEIVE_BOOT_COMPLETED` in the AAR would be in every host's manifest |
| A start that is late | It begins late | The contract already says that a time that has passed begins at once. A host that does not want a late one ends it, or gives it a stale time |
| An update before the time | `CONFLICT` | As `liveactivity-rs` says of iOS. The activity begins with the content of its start; a host that needs other content ends it and schedules again |
| Foreground placement | Refused with a start time | No service holds the notification at that time, and the library starts none |
| Schema version 1 | Refused | As every later extension |

## Options considered

| Option | Why not |
| --- | --- |
| The host keeps its own alarm and calls `start` | Every host writes the receiver, and Rust must load in a process that Android started for a broadcast |
| WorkManager | A dependency for every host, and a worker is no more exact than an inexact alarm |
| A foreground service that waits | A notification while nothing happens, for a time that may be days away |
| The receiver writes the receipt as started | Two writers of the receipts, and Rust's read and write are separate calls: one of them loses |

## Success criteria

- A start scheduled a few seconds ahead on the emulator is not shown before its time and is shown
  after it, posted by the receiver from the stored receipt.
- The same from Rust through JNI: `scheduled`, then `active`, each answered once.
- An end before the time leaves no notification and no alarm.
- A receipt stored for a time that passed while no alarm was set begins at the next lifecycle call.
- Tests are written and seen failing first; the host gates and the device suite stay green.

## Non-goals

- A clock or a thread in the crate.
- An alarm for `stale`, or one that ends an activity.
- A repeating start.
- Starting a host's foreground service at the time.
- A guarantee of the minute without the exact-alarm permission: Android does not give one.
- The mapping in `liveactivity-rs`.
