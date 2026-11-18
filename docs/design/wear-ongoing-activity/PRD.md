# Wear OS Ongoing Activity PRD

## Problem

The crate posts a live activity as an ongoing notification. On a phone with Android 16 that is a Live
Update. On a watch it is a line in the notification tray that the person has to swipe to.

Looked up and tried on 2026-11-19, on a Galaxy Watch4 Classic (`SM-R880`, Wear OS 5, Android 14, API 34,
32-bit ARM):

- **The library runs on the watch as it is.** The instrumentation suite, built for `armeabi-v7a`, has
  three runs of 77, 7 and 2 tests: 63, 6 and 2 pass, the Rust and JNI ones among them. The 14 and the 1
  that fail assert what Android 15, 16 or 17 do.
- **Live Updates come to watches with Wear OS 7** (Android 17), by Google's documentation. Below, by
  the same, the surface for an activity in progress is the Ongoing Activity: an icon on the watch face that
  opens the application, and an entry with a status in the recent applications. It is data of
  `androidx.wear:wear-ongoing` inside an ongoing notification.
- **The watch takes that data from a notification this library builds.** A plain ongoing notification
  left the watch face as it was. The same notification with the Ongoing Activity data, copied from the
  library's builder into the extras of the platform builder, put the icon on the watch face.

## Goal

An application that runs on a watch and has the Ongoing Activity library gets its live activity on the
watch face and among the recent applications, from the same start and update calls, without a new member
in a request.

## Required capabilities

- On a watch below Wear OS 7, when the application has `androidx.wear:wear-ongoing`, every notification of
  a live activity carries an Ongoing Activity: the notification's small icon, its content intent, its
  title, its status text and its header clock, if it has one.
- `capabilities()?.presentation.wear_ongoing_activity` says whether that happens.
- The AAR keeps no dependency of its own: an application for phones adds nothing and gets nothing.
- A phone, a watch with Wear OS 7, an application without the library: the notification is what it was.
- The test tooling builds for `armeabi-v7a` when asked, so that the suite runs on a watch.

## Options considered

| Option | Why not |
| --- | --- |
| The library as a dependency of the AAR | The AAR is a file without dependencies, and every phone application would carry AndroidX libraries for watches |
| Writing the data without the library | Its format is `androidx.versionedparcelable`'s, with AndroidX class names inside, and not documented |
| A member of the request that asks for it | There is nothing to choose: the icon, the intent and the status are the notification's |
| A second artifact for watches | Two AARs to release for one class |
| An animated icon and a custom status | Left out until an application needs them |

## Success criteria

- On the Galaxy Watch4 a live activity started through the adapter shows its icon on the watch face.
- The device suite reads the Ongoing Activity back from the posted notification: icon, intent, title,
  status and clock.
- The emulator suite of API 37 passes, with the Ongoing Activity asked for by the test, and not there
  otherwise.
- Tests are written and seen failing first; the host gates stay green.

## Non-goals

- Live Updates on Wear OS 7: the promoted notification of this library is expected to be one there, and
  no such device or image was run.
- Showing a phone's live activity on a paired watch. Android does not bridge ongoing notifications, and
  bridges Live Updates from Wear OS 7 where the manufacturer allows it.
- Samsung's Now Bar on a watch (One UI 8 Watch): no developer interface to it was found.
- A tile, a complication or a watch face.
