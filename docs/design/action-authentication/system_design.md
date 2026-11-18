# System design

## Which action requires authentication

Two things say it, and either is enough:

- **The intent.** The broadcast of an action has the boolean extra `authenticationRequired`. The
  intent's identity is its activity and its action, as before, and a post updates the extras of the
  intent that an earlier post made: an update that changes the requirement changes it for the
  notification that is shown.
- **The stored receipt.** A notification that an earlier version posted has an intent without the
  extra, and stays on screen across an update of the application until its activity is posted again.
  The receipt of its activity has the action and what it requires.

Receipts that cannot be read protect every action: the tap is dropped while the device is locked.
Without a receipt, as for the last content of an activity whose tombstone went, the intent alone says
it.

## The receiver

A tap is dropped when all three hold:

1. the device runs below API 31, where Android does not ask;
2. the action requires authentication;
3. `KeyguardManager.isDeviceLocked()` says the device is locked.

Then no event is stored and no listener is told, and a line is logged. Nothing else changes: the
notification stays, and a tap from the unlocked device is taken. The dropped tap is not kept.

Each is asked only when the ones before hold, so from API 31 the receiver reads neither the receipts
nor the lock and does what it did.

`isDeviceLocked` is true while a pattern, a PIN, a password or a biometric is needed to use the device.
A device whose lock screen needs no credential is not locked in that sense, and its taps are taken.

The rule is in `ActionTap`, which the receiver builds with the device's API level and lock, so the
public receiver keeps the one constructor Android uses.

## What the person sees

On Android 8 to 11, a tap on such a button from the lock screen does nothing. After unlocking, the
button works. There is no prompt: Android shows one for an activity, and an action of this library is a
broadcast.

## Limits

- No device below API 31 was run. The rule is tested as a function and through `ActionTap`, with the API
  level and the lock given to it.
- What a manufacturer's system UI does with a broadcast on a locked device below API 31 was not read:
  the sources are not public. The rule holds whatever sends the intent.
- What Android 12 does for such an action on a lock screen that needs no credential was not read. Below
  API 31 the tap is taken there.
- Android's sources note that `isDeviceLocked` follows the unlock with a delay
  (`NotificationLockscreenUserManagerImpl`). A tap in the instant after an unlock can still be dropped.
