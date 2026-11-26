# System design

## `local_only`

`AndroidPresentation { local_only: true, .. }`, in JSON `"localOnly": true`, left out when false. The
adapter calls `setLocalOnly(true)`: a paired watch does not get the notification, and on a watch a phone
does not. The lifecycle refuses it with `UNSUPPORTED_FEATURE` where the capability `localOnly` is false.

## Settings of promotion

`LiveUpdate::open_promotion_settings()` sends `settings` with `{}` and expects `null`. The AAR:

1. From API 36, `android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS`; before, or where nothing answers it,
   `Settings.ACTION_APP_NOTIFICATION_SETTINGS`; each with `Settings.EXTRA_APP_PACKAGE`.
2. `FLAG_ACTIVITY_NEW_TASK`, started from the application's context.
3. `INVALID_INPUT` never; `NATIVE_ERROR` when no screen answers or Android refuses the start.

Android starts an activity only for an application in the foreground: call it from a person's tap.

## Channel names

The AAR reads its four strings each time it makes sure of its channels, and Android takes a new name or
description for an existing channel. An application overrides them in its own `res/values*/strings.xml`.
