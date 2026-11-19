# What a device shows PRD

## Problem

The tests read the notification that the adapter built. Nobody had looked at what a phone and a watch
draw of it, option by option. On 2026-11-19 the notifications of 0.7.0 were looked at by hand on a
Galaxy S23 (Android 17, One UI 9) and a Galaxy Watch4 (Wear OS 5): the ends of a progress bar, the
sizes of a card, rotation, a right-to-left layout, the lock screen, the banner of a revision that
alerts, the status chip and the Now Bar ([testing.md](../../testing.md)). Three things were the
library's own:

- **The watch keeps the icon of an Ongoing Activity that an update turned off.** The notification no
  longer carries one, and the icon stays on the watch face until the notification is cancelled.
  LU-WEAR-6 says an update may change it.
- **`ProgressStyle::new` builds a bar that shows no progress.** It sets `styled_by_progress` to false,
  where Android's own default is true. Without a tracker icon the bar looks the same at 0 and at 100.
- **A decimal metric reads differently by Android version.** API 37's `MetricStyle` shows at most two
  fraction digits: 3.14159 is 3.14, 0.0004 is 0. The text that 0.7.0 shows below API 37 wrote every
  digit.

The rest is what One UI makes of the options, which the README did not say.

## Goal

What the library says of an option is what the devices show, and where the system UI decides, the
README says what it was seen to do.

## Required capabilities

- On a watch a post without the Ongoing Activity takes the icon away, for a presentation that turns
  it off and for the last content of an ended activity.
- `ProgressStyle::new` builds a style that is styled by its progress. The JSON of the member, and with
  it every stored receipt and every request of an earlier version, reads as before.
- The metric text shows a decimal with at most two fraction digits, as API 37 shows the same value.
- The README says what One UI 9 was seen to do with `samsung_now_bar`, a point at the track's end, the
  critical metric, long metric values and a right-to-left layout, and what a watch shows.

## Options considered

| Option | Why not |
| --- | --- |
| The JSON default of `styledByProgress` becomes true | A receipt and a request of an earlier version leave the member out and mean false. They would change their meaning |
| The adapter styles by progress whenever there is no tracker icon | False is a real choice: an application may color the segments itself, as Android's documentation says |
| Cancel and post at every update on a watch | A cancel is heard by every listener and redraws the watch's surfaces. It is needed only when the Ongoing Activity goes |
| Take a service's notification out of the foreground to cancel it | A service that leaves the foreground in the background may not come back |
| Leave out `samsung_now_bar` | An application may want exactly those two lines. The README says what it costs |

## Success criteria

- On the watch the icon goes when an update turns the Ongoing Activity off and when the activity ends
  with a last content, seen on the watch face.
- Tests are written and seen failing first; the host gates and the mutants of the changed lines pass.

## Non-goals

- What One UI draws: the clock that touches the title, the summary that a sub text hides, the card
  that shifts after an update in a right-to-left layout. They are the system UI's.
- A notification that a foreground service holds on a watch: it cannot be cancelled, and keeps its
  icon until the activity ends.
