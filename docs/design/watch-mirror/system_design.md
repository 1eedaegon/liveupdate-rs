# System design

## The member

`AndroidPresentation { watch_mirror: true, .. }` is `"watchMirror": true` in JSON, and is left out when
false. The lifecycle refuses it:
- with `UNSUPPORTED_FEATURE` where the capability `watchMirror` is false: a watch, or an AAR before the
  mirror;
- with `INVALID_INPUT` beside `local_only: true`.

The check is the same for a start, an update and a scheduled start.

## The adapter, at each post of an activity

1. The activity's own notification is built as before. With `watchMirror` it is also local-only.
2. Then the mirror, under the tag `liveupdate-rs-watch:` + id and the notification id 1:
   - **Removed** when the post removes the last content, when the presentation has no `watchMirror`,
     when the adapter does not offer it, or when the person dismissed it.
   - **Posted** otherwise.
3. A cancel of the activity cancels its mirror and forgets its dismissal.

## The mirror's notification

The renderer builds it from the same record, with the activity's content: title, status, style,
icons, color, actions and content intent. It differs from the activity's notification in these ways:

- It is not ongoing, carries no request for promotion and no Ongoing Activity, and is not local-only.
- It has no record extra: the bridge carries the extras to the watch.
- Its delete intent is the mirror's (`liveupdate://mirror/<id>`), and none for the last content.
- It is in the group `liveupdate-rs-watch:<id>`, so that Android does not bundle it with the activity.
- Its channel is the mirror's, `liveupdate-rs.watch.v1`, at importance MIN: quiet on the phone and on
  the watch.
- A revision that alerts goes on the alerting channel at HIGH, so the watch vibrates. Whether it can then
  be silent on the phone (`setSilent`) while the watch still vibrates is measured before it is decided.
  If it cannot, the phone alerts for the activity and the mirror alike, and the README says so.
- The last content keeps the activity's time to go (`setTimeoutAfter`), so both go together.

## The person's no

The mirror's delete intent reaches `LiveUpdateDismissReceiver` with the action
`io.github.eedaegon.liveupdate.MIRROR_DISMISSED`. The adapter adds the id to a set in its preferences.
The set is bounded by the stored receipts: an id that is no longer stored is dropped. Android sends no
delete intent for a cancel by the application. A dismissal that the bridge echoes back after the
application's own cancel reaches no notification, so it changes nothing.
