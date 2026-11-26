# Watch mirror PRD

## Problem

A phone's activity does not reach a paired watch. Android's bridging leaves ongoing notifications
out, and Live Updates are ongoing. The bridging guide says that Live Updates may still be bridged,
depending on the Wear OS version and the maker. On 2026-11-26 Galaxy Wearable did not bridge them to
a Galaxy Watch4 Classic on Wear OS 6, paired with the S23. Its bridge logged
`NotificationChecker.checkIgnoreCase - onGoing`, then `Discarding notification`. That happened with the
application allowed for the watch, the watch worn, and the phone's screen either on or off.

The library serves a watch application already: its activity is an Ongoing Activity on the watch
face. A phone application has nothing on the watch. The owner asked for both.

## What the bridge does, measured

On 2026-11-26 a throwaway application posted plain notifications on the S23 while the Galaxy Watch4
Classic was worn. It is in no commit.

- A notification that is not ongoing reaches the watch at importance MIN and at LOW, without
  vibrating. At DEFAULT and at HIGH it reaches the watch and vibrates it.
- The bridge ignores `FLAG_ONLY_ALERT_ONCE`: every update of a HIGH notification vibrated the watch again.
- An update with the same tag and id replaces the watch's card in place.
- A cancel on the phone removes the card from the watch. A dismissal on the watch cancels the
  notification on the phone, which sends its delete intent.
- The card shows the large icon (cropped round) or the application's icon, with an application badge.
  It shows the title, the time, the text, and the color as a tint. It shows no progress bar. Its
  content intent and its actions are carried, and a tap on the watch runs them on the phone.
- While the watch is off the wrist, the bridge queues a notification (`NOT_WEARING_WATCH`) and sends it
  once the watch is worn.
- A notification in an explicit group is bridged. A promoted notification in an explicit group stays
  promoted.

## Goal

A phone application can show its activity on a paired watch through the bridge that the watch already
has, and needs no watch application to do it.

## Required capabilities

- `AndroidPresentation::watch_mirror: bool` (JSON `watchMirror`, left out when false). With it, the AAR
  posts a second notification beside the activity's own: the mirror. The mirror is not ongoing and
  not promoted, and it carries the same content.
- A capability, `watchMirror`, is true on a phone with an AAR that has the mirror. It is false on a watch,
  and from an earlier AAR. The lifecycle refuses a request that asks for the mirror where the capability
  is false (`UNSUPPORTED_FEATURE`), as it refuses an action target.
- `local_only` and `watch_mirror` together are contradictory: `INVALID_INPUT`.
- The activity's own notification is local-only while it is mirrored. A watch whose bridge takes Live
  Updates then shows one card, not two.
- Revisions:
  - An ordinary revision updates the mirror quietly: importance MIN on the phone, and no vibration on the
    watch.
  - A revision with `alert: true` goes to the mirror through the alerting channel, and the watch vibrates.
    The activity's own notification does not alert then: the mirror alerts the phone. The bridge mutes a
    notification that the phone keeps quiet by its group, so a mirror cannot vibrate the watch alone.
- The end:
  - The mirror carries the last content and goes at the same time.
  - An immediate end removes it.
  - An update that turns the mirror off removes it.
- A person who dismisses the mirror, on the watch or on the phone, has said no. That activity is not
  mirrored again.
- The mirror's channel is named by string resources that an application overrides, as the other channels
  are.

## The cost

The phone lists the mirror as a second notification. At importance MIN, One UI shows it in the silent
section, collapsed to one line (seen on the S23). It is opt-in for that reason.

## Out of scope

- A progress bar on the watch: the card has none. The status text says what the bar says.
- Mirroring on a watch, for the watch's phone.
- An event for the mirror's dismissal: the activity goes on, on the phone.

## Success criteria

- Rust: the member's JSON, its refusal without the capability, and the contradiction with `local_only`.
- Device tests:
  - The mirror is posted under its own tag, on its own channel, and is not ongoing.
  - The activity's own notification is local-only while mirrored.
  - An alerting revision goes on the alerting channel, and an update without the member removes the mirror.
  - The end gives the mirror the last content and takes it away at its time.
  - A dismissal stops the mirror.
  - The lifecycle's observation ignores the mirror.
- On the S23 with the Galaxy Watch4 worn: the consumer application's mirror is on the watch, it is
  updated in place, it vibrates only for an alerting revision, and it goes when the activity ends.
- Tests are written and seen failing first.
