# Native targets PRD

## Problem

A tap on an action of a live activity goes one of two ways today:

- To the queue: the AAR's receiver stores an `action` event, tells the one listener, and the application's
  Rust drains the queue (`LiveUpdate::events()`). In a process that no activity and no Rust runtime keeps,
  the tap waits there until they run.
- To a screen: an action with `open_url` opens an activity of the application, which reports the opening
  (`LiveUpdateEvents.opened`).

Neither runs the application's own code at once without a screen. A recording, a playback or a timer
controlled from the notification needs exactly that, with the screen off, from the lock screen, while only a
service of the application runs. Fremen, a Tauri application, keeps its Android session in a foreground
service of type microphone, whose own notification has a Record action that goes straight to the service
(`PendingIntent.getService`). It wants that notification to come from liveupdate through the Live Activity
plugin, as its iPhone's Live Activity does, and cannot today:

- A tap would only be stored, and the service would not hear of it while the screen is off.
- The service cannot listen itself: there is one listener, which the plugin uses, and draining the queue
  consumes it.
- Nothing documented lets a service start, update and end an activity without an activity or a web view.
- A foreground service must be in the foreground within seconds of its start. `LiveUpdateForeground.attach`
  answers false when no activity holds the placement, and every service then writes the same fallback; when
  the activity ends, the service leaves the foreground, which a recording service cannot.

On iOS none of this is needed: a Live Activity's button is an App Intent that iOS runs in the application's
process.

## Goal

An action can deliver its tap to a component of the application itself, which takes it at once and records
it, so that Rust and the page see it without acting twice; native code can watch every event without taking
it from the queue; a service can drive an activity through the application's Rust; and a foreground service
always has a notification.

## Required capabilities

- **A target of an action** (Android only): a service, a foreground service or a broadcast receiver of the
  application's own package, by its class name, with an optional intent action. The renderer builds an
  explicit, immutable PendingIntent to it with the activity's and the action's ids, instead of the AAR's
  receiver. A target that the application does not have is refused, and so is a target beside a link.
  `authenticationRequired` holds for it as for any action. A capability, `actionTargets`, says the adapter
  offers it; the lifecycle refuses an action with a target where it does not, since an earlier AAR would
  send the tap to the queue instead.
- **`LiveUpdateActions.take(context, intent)`**, which the target calls first. It answers the tap's
  activity and action, or nothing for an intent that is no tap of a target. Below Android 12 it applies the
  lock rule of the AAR's receiver and answers that the tap is withheld. Otherwise it records the tap as an
  event of a kind of its own, `handled`, and tells the listeners. It never throws.
- **Listeners that do not consume**: `LiveUpdateEvents.addNativeListener` and `removeNativeListener`. Each
  is told of every stored event with its content. The queue and the one listener stay as they are.
- **Rust from any thread of the application's process**: `LiveUpdate::android_from_raw(vm, context)`, for a
  service's own JNI entry, without `ndk-context`.
- **A foreground service that is never without a notification**:
  `LiveUpdateForeground.attachOr(service, type, fallbackId, fallback)` shows the activity's notification, or
  the application's fallback when no activity holds the placement, and goes back to the fallback when the
  activity ends, without leaving the foreground. A later `attach` takes the fallback away.
- **Documentation** of a service that does all of it, in Kotlin and in Rust, and its tests on a device.

## Options considered

| Option | Why not |
| --- | --- |
| Route every tap through the AAR's receiver and forward it to the service | Starting a service from a broadcast receiver meets Android's limits on background service starts, and adds a hop that can fail |
| A flag on the `action` event for a tap that was handled | A crate of 0.7 ignores a member it does not know and would act on the tap. A kind of its own reads as `Unknown` there |
| A lifecycle in Kotlin for services | Two lifecycles to keep alike. The application's Rust runs in the service's process |
| A target by intent action and package alone | A service then needs an intent filter, which exports it. A class name needs neither |
| `jni` types in `android_from_raw`'s signature | The crate's API would follow the `jni` crate's versions. Raw pointers are what `ndk-context` takes too |
| Make the one listener a list | The plugin sets it and expects to replace it. A second kind of listener leaves it as it is |

## Success criteria

- On a device, an action with a target reaches a service of the application with no activity, with the
  screen off and from the lock screen; Rust then drains a `handled` event; actions without a target behave
  as before.
- A service attached with a fallback is in the foreground the whole time: with the fallback, with the
  activity's notification, and with the fallback again after the end, never with two notifications.
- Tests are written and seen failing first; the host gates and the mutants of the changed lines pass.

## Non-goals

- A toggle's state in the tap: the crate has no toggle, and the application knows its own state.
- A target in another package.
- An earlier AAR: it does not know a target, and the lifecycle refuses one there.
