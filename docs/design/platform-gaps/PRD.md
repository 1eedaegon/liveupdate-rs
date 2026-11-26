# Platform gaps PRD

## Problem

Measured against Android's notification guides (the overview, Live Updates and progress-centric
notifications), the library covers the examples of a Live Update and of a progress journey, and leaves
out some of what the guides name:

- **Keeping an activity off a watch.** The guide: notifications appear on paired watches by default, and
  `setLocalOnly()` keeps one on the device. The library has no such switch.
- **The settings of promotion.** The Live Update guide names `Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS`,
  where a person allows or stops an app's Live Updates. An application that reads `promotionEnabled: false`
  has nothing to send the person to.
- **The channels' names.** The AAR names its channels "Live updates" and "Live update alerts", in English,
  in every application. A person sees those names in the system's settings; an application cannot put
  its own, or its own language.
- **`CallStyle`**, a template that the Live Update guide lists among the promotable ones.

## Goal

An application can keep an activity off a paired watch, open the settings of its Live Updates, and name
the channels in its own words and languages. `CallStyle` is decided on evidence.

## Required capabilities

- `AndroidPresentation::local_only`: the activity stays on the device it is posted on. A capability,
  `localOnly`, says the AAR offers it; the lifecycle refuses a request that asks for it where the AAR does
  not, since an earlier AAR would send the activity to the watch.
- `LiveUpdate::open_promotion_settings()`: the system's screen of the application's Live Updates from
  Android 16, or of its notifications before that and where that screen is missing. An earlier AAR does
  not know the command and answers `UNSUPPORTED_FEATURE`.
- The channels' names and descriptions are string resources of the AAR, which an application overrides as
  any library resource, in every language it has. An existing channel takes the new name.

## `CallStyle`: not added

On the Galaxy S23 (Android 17, One UI 9), with a throwaway test of the platform's own API:

- Posted without a foreground service, Android refuses a `CallStyle` notification: `IllegalArgumentException:
  Not posted. CallStyle notifications must be for a foreground service or user initated job or use a
  fullScreenIntent.`
- Held by a foreground service it shows the call template, and `hasPromotableCharacteristics()` is false:
  it is never promoted, ongoing, incoming or screening, with or without a chronometer, the call category,
  a chip, or colorized set false. The same notification without the style is promotable.
- When the service detaches, Android posts it again as an ordinary notification, refuses that, and ends the
  application's process: `CannotPostForegroundServiceNotificationException: Bad notification for
  startForeground`.

So a call template is no Live Update on that device, and holding it safely means keeping it out of every
post but the service's. It is left out until a device promotes it; an application with calls uses
`android.telecom` and its own notification.

Out of scope as before, since Android does not promote them: replies (`RemoteInput`), suggested actions,
groups, `BigPictureStyle`, `InboxStyle`, `MessagingStyle`, bubbles and custom `RemoteViews`.

## Success criteria

- A device test reads `FLAG_LOCAL_ONLY` from a posted notification with the member, and not without it.
- On the S23, the settings screen opens from Rust, and the consumer application's own channel names are
  what the system shows.
- Tests are written and seen failing first.
