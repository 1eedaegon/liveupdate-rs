# Technical design

- `LiveUpdateReceivers.kt`: `AUTHENTICATION_EXTRA`; `withholdsAction(sdk, authenticationRequired,
  deviceLocked)`, the rule; `deviceLocked(context)`, the keyguard's answer; `ActionTap`, what the receiver
  did, built with the API level and the lock.
- `AndroidAdapter.requiresAuthentication(id, actionId)`: what the stored receipt says.
- `NotificationRenderer.actionBroadcast`: puts the extra.

No manifest entry, no JSON member and no Rust code changes.

## Tests

Written first and seen failing.

1. Kotlin (`ActionAuthenticationTest`): the rule for each combination of requirement, API level and
   lock, and that each question is asked only when the tap depends on it.
2. Device (`RichPresentationInstrumentationTest`): the intents of a posted notification say which action
   requires authentication; given API 30 and a locked device, a tap is dropped and no listener told when
   the intent, the stored receipt, or receipts that cannot be read require it, and taken when unlocked,
   from API 31, or when nothing requires it; `deviceLocked` answers as the keyguard does.

## Sources

- `Notification.Action.Builder#setAuthenticationRequired`, added in API level 31:
  <https://developer.android.com/reference/android/app/Notification.Action.Builder#setAuthenticationRequired(boolean)>
- `StatusBarRemoteInputCallback.handleRemoteViewClick`, `android11-release` and `android12-release`:
  <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/packages/SystemUI/src/com/android/systemui/statusbar/phone/StatusBarRemoteInputCallback.java#246>,
  <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/packages/SystemUI/src/com/android/systemui/statusbar/phone/StatusBarRemoteInputCallback.java#255>
- AndroidX, `NotificationCompatBuilder`:
  <https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/core/core/src/main/java/androidx/core/app/NotificationCompatBuilder.java#366>
- `KeyguardManager#isDeviceLocked`:
  <https://developer.android.com/reference/android/app/KeyguardManager#isDeviceLocked()>
