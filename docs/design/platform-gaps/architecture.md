# Architecture

| Need | Crate | AAR |
| --- | --- | --- |
| Off the watch | `AndroidPresentation::local_only`, checked against `presentation.local_only` | `Notification.Builder.setLocalOnly` |
| Settings of promotion | `LiveUpdate::open_promotion_settings`, `Driver::open_promotion_settings` (default: unsupported), the bridge command `settings` | Starts the settings screen as a new task |
| Channel names | — | `res/values/strings.xml`: `liveupdate_channel_name`, `liveupdate_channel_description`, `liveupdate_alert_channel_name`, `liveupdate_alert_channel_description` |

The settings command is the bridge's first that does not touch a notification. A `Driver` that does not
implement it answers `UNSUPPORTED_FEATURE`, so a custom driver keeps compiling.
