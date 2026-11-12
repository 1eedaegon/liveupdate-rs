# System design

Every member below is optional, belongs to schema 2 and is left out of the JSON when unset.

## Contract additions

| Member | Where | Value |
| --- | --- | --- |
| `openUrl` | presentation, action | A URL the application's package opens |
| `chronometer` | presentation | `{ "timer": { "startedAt", "endsAt" }, "countsDown" }` |
| `alert` | presentation | `true`: this revision alerts |
| `publicVersion` | presentation | `{ "title", "status" }` for the secure lock screen |
| `smallIcon` | presentation | A resource icon |
| `accentColor` | presentation | Unsigned ARGB |
| `subText` | presentation | A short header text |
| `placement` | start request | `"foreground"`; absent means the tagged key of today |
| `dismissAt` | stored receipt | The time of an `EndPolicy::After`, set by the lifecycle |
| `opened` | event kind | `{ "kind": "opened", "actionId"? }` |

Capabilities, one each, false unless the adapter reports it: `links`, `chronometer`, `styleChange`,
`delayedEnd`, `foregroundPlacement`, `alert`, `publicVersion`, `branding`.

## Links

A URL has 1 to 2048 printable ASCII characters: a lowercase scheme, a colon and something after it.
The schemes `intent`, `content`, `file`, `javascript`, `data` and `android-app` are refused.

- With `openUrl`, the content tap opens the URL in the application's package; without, it opens the
  launch activity as today. Either intent carries the activity's id, and is distinct per activity.
- An action with `openUrl` opens it and queues no `action` event: Android does not let a
  notification's receiver start an activity. Its intent carries the activity's and the action's id.
  `authenticationRequired` applies to both kinds of action.
- A URL that no activity of the package handles fails the post, and nothing is posted.
- The opened activity may hand its intent to `LiveUpdateEvents.opened(context, intent)`, which queues
  one `opened` event for an intent of this crate and answers whether it did. A host calls it for a new
  intent, not for a restored one.

## Chronometer

The header clock of any style: up from `startedAt` or down to `endsAt`. It is refused beside the
`timer` style, which is that clock alone. A metric's own stopwatch or countdown is independent of it.

## Style change

A schema-2 update may carry any system style. The record's `template` names the style of its latest
snapshot; the request of the start stays the origin, and the same start still answers the running
activity. Promotion and fallback stay what the start decided. Schema 1 keeps its template.

## Delayed end

`end(id, EndPolicy::After { at })` stores the record as `ended` with `dismissAt`, then posts its final
form instead of cancelling: the last snapshot, no longer ongoing, without the promotion request, with
`setTimeoutAfter`. Android removes it at `at`; the user may remove it sooner.

- An `at` that has passed when the adapter posts cancels at once.
- The adapter no longer observes the final form. Reconciliation therefore stays as it is: an ended
  record that is still observed, as after a failed final post, is cancelled.
- An activity the user already removed is ended without a final post.
- The final snapshot of an end is an update followed by the end.
- `EndPolicy::Default` stays immediate.

## Foreground placement

A foreground service holds a notification by an id alone. An activity that a service will hold says
so at start, and is keyed without a tag from its first post, so that nothing is posted twice.

1. `start` with `placement: "foreground"` posts under `LiveUpdateForeground.NOTIFICATION_ID`, as an
   ordinary notification. One live activity may have this placement; a second start fails with
   `SystemLimit`. The placement is part of the request: another placement under the same key is a
   `Conflict`.
2. The host starts its service, which calls `LiveUpdateForeground.attach(service, type)`. The adapter
   builds the notification from the stored receipt and calls `startForeground` with the same id: the
   posted notification becomes the service's. `attach` answers false when no live activity has the
   placement.
3. `update` shows the newer content through the attached service, or posts under the same id when no
   service holds it. When a service detaches, Android posts the notification it was last given once
   more, so a held activity is never updated past its service.
4. `end` takes the attached service out of the foreground with its notification, since Android ignores
   the cancel of a foreground notification, and tells the host's listener, which stops its service. A
   delayed end does the same and posts the last content under the activity's own tag.
5. `LiveUpdateForeground.detach(service)` leaves the notification as an ordinary one.

A process that dies takes its foreground notification with it. The next reconciliation then finds a
live record without a notification, which is `dismissed`, as for any notification the system removed.
A restarted service that attaches before the first lifecycle call posts the stored receipt again, and
the activity stays live.

The notification is posted with `FOREGROUND_SERVICE_IMMEDIATE` from API 31, so that Android does not
hold it back.

## Alerting revision

A snapshot with `alert` is posted on a second channel the adapter creates (`liveupdate-rs.alerts.v1`,
high importance) and may alert again; the next snapshot without it returns to the quiet channel. A
post that is retried may alert twice. When the user blocked the alerting channel, the revision is
posted on the quiet one.

## Public version

A title and a status that replace the content on a secure lock screen. Only a `private` activity may
have one; with another visibility the request is invalid.

## Branding

`smallIcon` is a resource of the application; `accentColor` colors the icon and the header;
`subText` is the header's third text. None of them changes promotion.

## Rendering matrix

| Extension | API 26-30 | API 31-35 | API 36+ |
| --- | --- | --- | --- |
| Links | activity intents | same | same |
| Chronometer | header clock | same | header clock; the chip may show it without a chip text |
| Delayed end | timeout | same | timeout; the final form is not promoted |
| Foreground | `startForeground(id, n)` from 26, with a type from 29 | immediate display | same, promoted |
| Alert, public version, branding | same on every level | | |

## Requirements

The rows are in `docs/spec.md` under "Android presentation extensions", with their tests. Rows that
changed: LU-ADAPTER-1 (key, small
icon), LU-ADAPTER-4 and LU-V2-ACTION-2 (the tap), LU-ADAPTER-6 (which notifications are the
adapter's), LU-UPDATE-5 and LU-V2-MODEL-1 (the template), LU-END-3 (the delayed end), LU-LIST-7 (an
ended notification that stays), LU-LIMIT-1 (one foreground activity), LU-CAP-3 (the second channel),
and LU-DOCS-2 (the README's sentence on `EndPolicy::After`). LU-V2-CAP-2 holds the new capabilities.

| Area | New rows |
| --- | --- |
| `LU-V2-LINK` | URL validation; content tap; opening action; unhandled URL; `opened` event |
| `LU-V2-CHRONO` | Model and its exclusion with `timer`; the built notification |
| `LU-V2-MODEL` | Style change across updates |
| `LU-END` | Delayed end in the lifecycle; the final form; reconciliation |
| `LU-FGS` | Placement in the request; the limit; the key; attach; update; end; detach; a dead process |
| `LU-V2-ALERT` | Model; the channel and the return to the quiet one |
| `LU-V2-PUBLIC` | Model with visibility; the built public version |
| `LU-V2-BRAND` | Model; the built notification |

## Failure behavior

Invalid members fail before a driver call. A URL without a handler, an icon that is not a resource
and a public version on a non-private activity fail the post and post nothing. A cancel that Android
ignores because a service the adapter does not know holds the notification leaves the record `ended`;
the next reconciliation cancels again (LU-END-5).
