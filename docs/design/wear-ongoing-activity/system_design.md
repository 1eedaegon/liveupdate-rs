# System design

## When a notification carries an Ongoing Activity

All of these hold:

1. The device is a watch: `PackageManager.FEATURE_WATCH`.
2. It runs API 30 to 36. Below, the watch ignores the data; from API 37, Wear OS 7, Google asks a watch
   application for a Live Update instead, which the promoted notification is.
3. The application has `androidx.wear.ongoing.OngoingActivity` on its class path.
4. The notification is ongoing and has a content intent. The last content of an ended activity is not
   ongoing (LU-END-9), and an application without a launch activity has no content intent
   (LU-ADAPTER-4).

The first three are asked once, when the adapter is made, and are what the capability
`wearOngoingActivity` reports.

## An activity without it

A presentation has the member `wearOngoingActivity`, true unless it says otherwise, in Rust
`AndroidPresentation::wear_ongoing_activity`. With `false` the notification carries no Ongoing Activity,
whatever the device offers, and is otherwise the same.

The member is written only when it is false. So a request, a receipt and a notification of an earlier
version, which have no such member, carry the Ongoing Activity as before, and an AAR of an earlier
version takes every record that does not turn it off. One that does, it refuses, as it refuses every
member it does not know: the AAR is the crate's version's.

It is content, as every member of a presentation: an update with another value is another revision, and
the next post follows it. Schema version 1 has no presentation and always carries the Ongoing Activity
where it is offered.

## What it is made of

The Ongoing Activity is read from the notification that the renderer built, so it shows what the
notification shows:

| Ongoing Activity | From the notification |
| --- | --- |
| Static icon | The small icon |
| Touch intent | The content intent |
| Title | The title |
| Category | The category |
| Status | The text; with a header clock, the text and the clock |

A header clock counts down to a time or up from one, in the device's wall clock. The Ongoing Activity
counts in the time since the device started, so the clock's time is moved by the difference of the two
at the post: `elapsed now + (time − wall clock now)`. A countdown becomes the library's timer part and a
clock that counts up its stopwatch part, in the template `#status# #time#`.

Every post builds the data anew. The library's own `update` is not used: it would post a notification
of its own.

## How it gets into the notification

The library writes into an AndroidX builder only. `WearOngoing` gives it an empty one, lets it write
there, and copies the data, one bundle under `android.wearable.ongoingactivities.EXTENSIONS`, into the
extras of the built platform notification with the library's own `SerializationHelper.copy`. The Galaxy
Watch4 takes it from there.

A notification that a foreground service of the host holds is the same notification, so it carries the
same data.

## An application without the library

On a phone nothing asks for the library. On a watch the adapter asks `WearOngoing` whether its class is
there; the class is named in code, not by a string, so a build that renames the library's classes still
finds it, and without the library the question ends in a `NoClassDefFoundError`, which is the answer no.
Nothing is then put into a notification.

The AAR's consumer rules tell R8 not to warn of the classes that `WearOngoing` names, and keep
`WearOngoing`, so that R8 does not move its code into the renderer, which every application loads.

## A library that fails

The library is the application's, in whatever version it has. When it throws, or lacks a member that
`WearOngoing` calls, the notification is posted as it is, without an Ongoing Activity, and a line is
logged. The capability does not change.

## The capability

`capabilities().presentation.wearOngoingActivity`, in Rust
`PresentationCapabilities::wear_ongoing_activity`. False on a phone, on a watch with API 37 or newer,
and without the library.

## The watch as a test device

A Wear OS watch of 2021 to 2024 has a 32-bit userspace. The instrumentation tests' Rust library is built
for `armeabi-v7a` when `-Pliveupdate.nativeAbis=armeabi-v7a` names it, with the Rust target
`armv7-linux-androideabi`, which the development shell now has. The device script still takes emulators
only: a watch is run by hand (`docs/testing.md`).

## Limits

- How the watch's recent applications show the status was not looked at; the watch face was.
- Wear OS 6 and 7 were not run. That the promoted notification is a Live Update on a watch with Wear OS 7
  is from Google's documentation. A notification that is not promoted there, because the request did not
  ask or the watch did not grant it, has neither a Live Update nor an Ongoing Activity.
- An application without the library was not run on a device or built with R8: the test package has it
  and is not shrunk. That no other class of the AAR names AndroidX is checked on the AAR.
- A watch below API 30 was not run; the library's own documentation says it is ignored there.
- The clock's time is right for the moment of the post. A change of the wall clock afterwards moves the
  notification's clock and not the Ongoing Activity's, until the next post.
