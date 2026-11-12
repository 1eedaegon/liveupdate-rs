# Technical design

## Rust modules

- `presentation`: the optional members of `AndroidPresentation` and `NotificationAction` (`open_url`,
  `chronometer`, `alert`, `public_version`, `small_icon`, `accent_color`, `sub_text`), a URL check
  beside the icon checks, and the exclusion of `chronometer` with the `timer` style.
- `model`: `Placement` on `StartRequest`; the visibility check of a public version at start.
- `contract`: the eight capabilities; `PresentationCapabilities` reads a missing member as false.
- `event`: `EventKind::Opened { action_id }`.
- `driver`: `dismiss_at` on `DriverRecord`; the public record's template comes from its snapshot.
- `client`: a schema-2 update keeps its origin and may change the style; `end` with `After` writes
  `dismiss_at` and posts; reconciliation is unchanged, because the adapter no longer observes a final
  form; a start with the foreground placement counts the live ones.

The Rust core still has no clock: whether a dismissal time has passed is the adapter's to say.

## Android modules

- `NotificationRenderer`: the opening intents, the header clock, the final form of an ended record,
  the public version, the small icon, color and sub text, and the choice of channel.
- `Links.kt` (new): the URL rule (`isValidLink`) and `LinkResolver`, the package-bound `ACTION_VIEW`
  intent.
- `AndroidAdapter`: the notification's key by placement, in `post`, `observe` and `cancel`; a held
  activity's updates through its service; the last content under the activity's tag; the second
  channel.
- `LiveUpdateForeground` (new, public): `NOTIFICATION_ID`, `attach`, `detach`, a listener; inside the
  AAR, the update and the release of the held notification.
- `LiveUpdateEvents`: `opened(context, intent)`.
- `EventStore`: the `opened` kind.

The manifest gains nothing. `tests/android/aar.sh` already fails on a service in the AAR.

## Platform behavior the designs rest on

Proven on the API 37 emulator by `ForegroundServiceInstrumentationTest`, with a foreground service
and a notification listener of the test package:

| Behavior | Result |
| --- | --- |
| A notification posted without a tag becomes the service's when `startForeground` names its id: same key, no removal heard, promotable characteristics kept | holds |
| A post under the same id keeps the service's notification | holds |
| A cancel does nothing while a service holds the notification | holds |
| `STOP_FOREGROUND_DETACH` leaves the notification, no longer the service's; `STOP_FOREGROUND_REMOVE` removes it | holds |
| A notification posted past the service stays when the service detaches, and a cancel right after removes it | does not hold: Android posts the notification the service was last given once more, a moment after the detach. A revision posted with `notify` came back as the older one, and a cancel was undone. The adapter updates a held activity through its service, ends it through the service, and posts its last content under the activity's own tag |
| A killed process takes its foreground notification with it | holds |
| Without `FOREGROUND_SERVICE_IMMEDIATE`, Android holds a service's notification back for about ten seconds | seen; the adapter asks for immediate display |
| More than five updates a second of one package are dropped by Android | seen in the device log; the tests post slowly enough, and the specification already names the limit |

Proven with the extensions themselves, by `ExtensionsInstrumentationTest`:

- A final post that is no longer ongoing replaces the notification in place, and `setTimeoutAfter`
  removes it (LU-END-9).
- A post on another channel replaces the notification in place (LU-V2-ALERT-2).

Not proven, because only the system UI shows it:

- Whether a promoted notification on a secure lock screen shows its public version.
- Whether the chip of a promoted notification shows the header clock without a chip text.
- Whether a post on the alerting channel keeps a notification promoted.

## Test design

1. Rust unit tests for each new member's bounds and JSON shape, and that requests without them keep
   their bytes.
2. Rust integration tests with the fake driver: the style change, the delayed end and its
   reconciliation, the foreground limit and conflict, the `opened` event.
3. Kotlin unit tests for URL checks and the parts that need no device.
4. Instrumentation tests that read the built notification back (intents, clock, public version,
   channel, key), and for the foreground placement a host service of the test package.
5. A notification listener of the test package wherever "replaced, not removed" is the claim: the
   active notifications cannot tell the two apart.

Tests are written and seen failing first. Each extension lands with its spec rows, so that
`tools/spec-coverage.sh` stays green commit by commit.

## Order of work

Links and the chronometer first: they remove what `liveactivity-rs` drops today. The style change and
the delayed end next, since both touch the lifecycle. Foreground placement after them, because a
delayed end of a held notification needs both. The alert, the public version and branding last; each
is a member and a builder call, and two of them wait for a device proof.

## Follow-ups outside this crate

- `liveactivity-rs`: an Android presentation spec bound to values; pass capabilities and dismissals
  through; `android.foreground`, `openUrl`, a lingering end; fold accessibility labels into texts.
- The Tauri plugin: declare the service and its type, call `LiveUpdateForeground` from it, hand opening
  intents to `LiveUpdateEvents.opened`.
- Both name this AAR's classes by package: the package is now `io.github.gon.liveupdate`.
