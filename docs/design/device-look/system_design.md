# System design

## The watch's icon

Wear OS keeps an Ongoing Activity by its notification. An update of that notification without the
library's data leaves the icon on the watch face: seen on the Galaxy Watch4, 16 seconds after the
update and until the notification was cancelled. A notification that never had one shows none, and
one that gains it by an update shows it.

So the adapter cancels before it posts, in exactly one case: it offers the Ongoing Activity, the
notification it is about to post has none, and the notification that Android shows for the activity
has one.

| Shown | Posted | What the adapter does |
| --- | --- | --- |
| With an Ongoing Activity | With one | Replaces it in place |
| With one | Without: turned off, or the last content of an ended activity | Cancels, then posts |
| Without | Any | Replaces it in place |
| Nothing | Any | Posts |

What is shown is known first from what this process posted last in that place, which the adapter
remembers until the notification is cancelled. Android lists a post only some time after it, so its
list alone would miss an update that is followed at once by an end. The list is asked only when the
process has posted nothing there, as after a restart, and only on an adapter that offers the Ongoing
Activity and for a notification without one: a phone never asks. A list that cannot be read keeps no
post back.

The application's cancel fires no delete intent, so no `dismissed` event is queued. The lifecycle
marks a record as awaiting Android's listing after every post, so the moment between the cancel and
the post is not read as a removal.

A notification that a foreground service holds is updated through the service and cannot be
cancelled. There the icon stays until the activity ends, when the service lets go of it.

## A progress style without a word on its styling

`setStyledByProgress` is true in Android unless an application says otherwise. With false, every
segment is drawn in full, and Android's documentation leaves it to the application to show the
progress by a tracker icon or by its own colors.

The crate's member is false when the JSON leaves it out, and is left out when false. That stays: a
receipt and a request of an earlier version mean false by leaving it out. What changes is the
constructor, which is a day old: `ProgressStyle::new` sets it to true. A struct literal names the
member as it always had to.

| Built by | `styled_by_progress` | On API 36 and later |
| --- | --- | --- |
| `ProgressStyle::new(progress, segments)` | true | What is ahead of the progress is fainter |
| A literal or JSON with `true` | true | The same |
| A literal with `false`, JSON without the member | false | Every segment in full; a tracker icon alone shows the progress |

Below API 36 the bar is Android's plain one and shows its progress either way.

## A decimal in the metric text

`Notification.Metric.FixedFloat` is built without a number of digits, and Android then shows at most
two fraction digits and no trailing zeros: on the S23 3.14159 was 3.14, 0.0004 was 0 and 0.33333334
was 0.33, and the notification's extras said `maxDigits=2, minDigits=0`. The text below API 37 does
the same, from the same float, so one value reads the same on every version. A negative value that
rounds to nothing is written as 0, without a sign; what API 37 makes of one was not looked at.

## What One UI does, for the README

Seen on the S23, and not the library's to change:

- A promoted activity is in the Now Bar by itself, and opened there it has its progress bar. With
  `samsungNowBar` the Now Bar and the card in the list show the two lines alone, with the
  application's icon and no progress bar.
- A point at the track's full length is not drawn; one a step before it is.
- The status chip shows the first metric. The notification names the critical one, and the chip does
  not follow it.
- Three metrics share the card's width: a value of more than about seven characters is cut with an
  ellipsis, a number too.
- In a right-to-left layout the card is mirrored when it is first shown and when its style changes.
- On the watch an ongoing notification is not among the notifications, and a revision that alerts
  does not pop up. The icon on the watch face is what an activity shows.
