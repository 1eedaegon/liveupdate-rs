# Activities from a push PRD

Written by the liveactivity-rs session at the owner's request (2026-11-26), against 0.9.0. Two corrections
are taken in: the constructor is `LiveUpdate::android_from_raw`, and the lock that serializes the
lifecycle becomes process-wide.

## Problem

An activity often changes while the app does not run. On iOS the server sends the content to APNs, and
the system draws it without the app (activitykit's `Payload`, seen on an iPhone XS). Android has no such
push. A data message wakes the app, and every host then writes the rest itself:

- a messaging service in Kotlin;
- a way from that service into the Rust lifecycle: the library loaded, a JNI entry of the host's own,
  and a client built from the service's `Context`. In a process that a message started, nothing has set
  `ndk_context`, so only `LiveUpdate::android_from_raw` works;
- the payload's format, its checks, and the order of pushes against the app's own calls.

A host whose UI is not Kotlin has no place for that code. Writing the lifecycle's rules again in Kotlin
(`fits`, `replaces`, capabilities, permission, revisions) is what `scope.md` names as the reason the
library exists. liveactivity-rs has `Event::Updated` and push-to-start on iOS, and can offer neither on
Android.

## Goal

A data message from the app's server begins, updates or ends an activity on Android, also while the
app does not run.
- It runs under the lifecycle's own rules, with one writer of the receipts.
- It takes one line in the host's messaging service.
- The app learns what a push did at its next lifecycle call.

## Required capabilities

- **An entry in the AAR:** `LiveUpdatePush.handle(context, data: Map<String, String>): Boolean`.
  - It answers false and does nothing for a message that is not the library's, so a host can share its
    service.
  - There is no Firebase dependency. The host passes `RemoteMessage.data`, and any provider that
    delivers a string map works.
- **The Rust lifecycle runs the push:** start, update and end, as the Rust API does. It runs through a
  JNI entry that liveupdate exports, with a client built from the `Context` it is given. The host names
  its library in the manifest's meta-data, or has already loaded it.
- **One serialization boundary per process:** a push that comes while the app's own client works waits
  for it.
- **The payload** is one member of the data map, `liveupdate`: JSON with a version,
  `{"v":1,"command":"update","activityKey":"…","snapshot":{…}}`. A `start` carries a `StartRequest`,
  and an `end` carries an `EndPolicy`. The activity is named by its `activity_key`, which the server
  knows; the id is made on the device.
- **A server builder** in a module that builds on any target: `Payload::update(key, snapshot).to_data()`.
  It checks the request as the lifecycle does. It refuses a payload over FCM's 4096 bytes with its own
  error, as activitykit's `Payload::to_json` does for APNs.
- **Order:** a stale push or a repeat ends as the lifecycle makes it end: `STALE_REVISION`, and the same
  revision with the same content changes nothing. The server owns the revisions of an activity it
  pushes to, and the docs say so.
- **What a push did** is a durable event: `pushed { command, activityKey, revision, outcome }`.
  liveactivity-rs maps it to `Event::Updated` or `Event::Started`, as on iOS. A refused push is an event
  with its code, not a silent drop.
- **A capability** says the path is there, so that liveactivity-rs can answer `UNSUPPORTED_FEATURE`
  with an earlier AAR.

## Decisions (proposals)

- **Where the rules run:** in Rust, loaded in the process that the message started. That keeps one
  implementation and one writer of the receipts, as the scheduled-start design decided. Start, update
  and end then cost the same.
- **No Firebase in the AAR.** The token is the host's (`FirebaseMessaging.getToken()`). Android has one
  token per installation, not one per activity.
- **Name the activity by `activity_key`.**
- **Unknown input:** members that a later server adds are left alone, as the bridge does. An unknown
  value is refused.
- **Priority:** high for a start, an end and an update that alerts; normal otherwise. Firebase
  deprioritizes high-priority messages that show nothing.

## Options considered

- **Kotlin applies the push:** two implementations, and two writers.
- **Kotlin posts at once and queues the push for Rust:** the screen shows content that no check has
  seen, and the receipts differ from it until the app runs.
- **Each host writes its own service and JNI:** what exists today.
- **WorkManager:** a dependency for every host, for a read, a write and a post.
- **A foreground service per activity:** a notification held for hours.

## Risks to measure

- **Loading the host's library** in a process that a message started: its `JNI_OnLoad` and static
  initializers, and the cold-start time. A library that cannot load without its UI cannot use the path,
  and the docs say how to tell.
- **FCM's limits:**
  - `onMessageReceived` gets a few seconds.
  - A high-priority message that showed nothing is deprioritized; compare `getPriority()` with
    `getOriginalPriority()`.
  - One UI's sleeping apps may hold messages back.
- **Promotion:** whether a promoted notification that a background process updates keeps its promotion
  and its placement.

## Success criteria

- An instrumentation test hands the entry a map in a process with no client yet. The notification
  changes, and the next lifecycle call drains one event.
- A stale push, a repeat, and pushes out of order leave what the lifecycle would.
- A push during the app's own update: both are serialized, and no receipt is lost.
- On a real device, with a test Firebase project of the owner's and its keys outside the repository:
  start, update and end by FCM with the app swiped away. Each is timed, and the delivered priority is
  recorded.
- Tests are written and seen failing first.

## Non-goals

- A Firebase dependency, a sending service, or keys in the repository.
- Push-to-start tokens (Android has none).
- Notifications that are not live updates.
- The liveactivity-rs mapping, which follows this release.
