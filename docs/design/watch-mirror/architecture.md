# Architecture

| Need | Crate | AAR |
| --- | --- | --- |
| Ask for the mirror | `AndroidPresentation::watch_mirror`, checked against `presentation.watch_mirror`; `local_only` with it is invalid | `AndroidAdapter` posts or removes the mirror beside every post of the activity |
| The mirror's content | — | `NotificationRenderer.mirror(record, …)`: the activity's build, not ongoing, not promoted, without the record's extra and the Ongoing Activity, on the mirror's channel or the alerting one |
| One card on a watch that bridges Live Updates | — | The activity's own notification is local-only while mirrored |
| A person's no | — | The mirror's delete intent goes to `LiveUpdateDismissReceiver` with its own action. The adapter keeps the ids in its preferences and drops them with the activity |
| Names | — | `liveupdate_watch_channel_name`, `liveupdate_watch_channel_description` |

The mirror has its own tag prefix, `liveupdate-rs-watch:`. Observation and listing match `liveupdate-rs:`
and the foreground id alone, so the lifecycle never sees the mirror. Every path that posts an activity
goes through `AndroidAdapter.post`:
- a start, an update and an end;
- a scheduled start's alarm;
- a held activity's update through its service.

The mirror is decided there. A service's attach builds the activity's own notification with the same
decision, so that it stays local-only while mirrored. An activity's notification can also go without
the adapter: a person swipes it, or a service stops. So its delete intent removes the mirror, and an
observation removes a mirror whose activity Android has not listed for the listing's grace.
