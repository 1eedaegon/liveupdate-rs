# Action authentication PRD

## Problem

An action may ask that the person unlocks the device before its tap is sent:
`NotificationAction::authentication_required`. The adapter gives that to Android with
`Notification.Action.Builder.setAuthenticationRequired`, which exists from API 31, and did nothing below.
0.4.3 documented that as "an older Android has no such setting, and the button works there as any
other". Looked up on 2026-11-19, that is worse than it reads:

- The setting is new in API 31. The Android 11 sources have neither the method nor a hidden field for it
  (`Notification.Action`, `android11-release`), and AndroidX drops the value below 31
  (`NotificationCompatBuilder`: `if (Build.VERSION.SDK_INT >= 31)`).
- On Android 8 to 11 the system UI waits for the unlock only when an action starts an activity or takes a
  reply. A broadcast is sent at once (`StatusBarRemoteInputCallback.handleRemoteViewClick`,
  `android11-release`: `if (isActivity) { dismissKeyguardThenExecute(…) } else { return
  defaultHandler.handleClick(); }`). Android 12 added `|| appRequestedAuth` to that condition.
- With Android's default settings a locked device shows the buttons: pulling a notification down on the
  lock screen opens the shade without the unlock unless the person hides sensitive content.

An action of this library is a broadcast. So on Android 8 to 11 a button that asks for authentication,
"Pay" or "Cancel the order", is taken from a locked device by whoever holds it.

## Goal

Below API 31 a tap of an action that requires authentication reaches the application only from an
unlocked device.

## Required capabilities

- The action's intent carries its requirement.
- Below API 31 the action receiver stores no event, and tells no listener, while the device is locked.
  The same tap after the unlock is stored.
- From API 31 nothing changes: Android asks for the unlock and then sends the tap.
- The post, the JSON and the Rust API stay as they are.

## Options considered

| Option | Why not |
| --- | --- |
| Say so in the documentation, as 0.4.3 does | The application asked for a protection and does not get it |
| Refuse the post below API 31 | An application could not show the activity on an older device at all without dropping its button |
| A trampoline activity as the action's intent, for which Android asks to unlock on every version | A new activity in every host's manifest and a different kind of intent for each API level, for a prompt that no device below API 31 was there to show |
| A capability that says whether Android asks | It tells the application and protects nothing; it can follow |

## Success criteria

- The rule is a function of the requirement, the API level and the lock, tested for each combination,
  and the receiver is tested with an API level and a lock given to it.
- The device suite on API 37 passes unchanged: nothing is held back where Android asks.

## Non-goals

- A prompt to unlock below API 31. The tap does nothing there until the device is unlocked.
- An action that opens a URL: it starts an activity, for which Android asks on every version.
- A run on a device below API 31: none was at hand.
