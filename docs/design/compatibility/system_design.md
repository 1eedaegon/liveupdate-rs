# System design

## A record at the adapter

The renderer had 26 closed lists of members, one for each object of a record. A list refused a
member that was not on it. Every member that is on a list is also read by a check of its own, which
refuses a wrong type, a value out of its bounds, and a required member that is not there.

The lists go, and the checks stay. Four things only the lists caught are checked on their own now:

| What | Before | Now |
| --- | --- | --- |
| `promotionRequested` is a boolean | Read below API 36 only through the list | Read on every API level |
| A presentation in a snapshot of schema version 1 | Not on the list | Refused by name |
| A progress or a timer beside a presentation | Not on the list | Refused by name |
| `reportedState`, which only a stored receipt has (LU-STATE-4) | Not on the list | Refused by name |

A member that the adapter does not know does nothing. The record inside the notification is the text
of the record as it came, as before, so the snapshot that an observation answers has the member, and
the crate that sent it finds its own snapshot. The capability of what the member asks for is false,
because an adapter that does not know a capability leaves it out (LU-V2-CAP-2): that is how an
application learns that the option is not shown.

A value that the adapter does not know is still refused: a style, a template, a visibility, a state,
an icon's kind, a metric's kind. Nothing says what to show instead of it.

The dispatcher does the same with a payload, and answers a command that it does not know with
`UNSUPPORTED_FEATURE`: `INVALID_INPUT` is the error of a request that is wrong.

## The event queue

The adapter checks its stored events at every append and every drain. A stored event of a kind that
it did not know failed the check, so no later event was stored and no drain succeeded. Now the check
is of what the adapter knows: the sequence, the activity, the time, that the kind has a name, and the
action id of an action and of an opened event. Everything else of an event stays as it was stored.

The queue is read only up to 1 MiB. Its own events are small, 128 of them far below that, so an
append did not measure. An event of a later version may be large, and a queue over the bound would
fail every later append and drain. So an append lets the oldest events go, counted as dropped, until
what it stores can be read again.

## A reply at the crate

`Error`, the envelope, `ActivityEvent` and `EventBatch` no longer refuse a member they do not know.
`EventKind` gains `Unknown`, which serde gives every kind without a variant.

An event of an unknown kind stays in the batch. Leaving it out would hide that something happened to
the activity, and would leave a gap in the sequence that a caller could take for a lost event.
`EventKind` was `#[non_exhaustive]` already, so a caller has a `_` arm for it.

A value that the crate does not know still fails the call, as `NATIVE_ERROR` with the library's
message: an error's code, an observed state, a start's outcome. A code is what a caller acts on, and
there is none to give; a state is what the lifecycle acts on. So a later AAR answers an earlier crate
with the values that crate has, and a new value comes with something only a later crate asks for.

## Two versions together

| Crate | AAR | A member the AAR does not know | A reply the crate does not know |
| --- | --- | --- | --- |
| This or later | This or later | Posted without it | Read without it |
| Later | Up to 0.6.0 | `INVALID_INPUT` for that request | — |
| Up to 0.6.0 | Later | — | An extra capability is read; an extra member of an event or an error, and a new kind, fail the call |

The last row is why the AAR adds nothing to an event and an error that a crate up to 0.6.0 reads.

Receipts are the crate's: the adapter stores and answers them as text. A crate reads the receipts of
every earlier version, by defaults for the members that came later. An earlier crate over the
receipts of a later one fails every call with `NATIVE_ERROR` when one of them has a member it does
not know. That stays so. The receipt is made of the request types, which refuse unknown members so
that a mistake in an application's JSON is found; a tolerant reading needs a second set of types for
the store, and going back to an earlier crate is rare.

## The Rust API

Every public enum is `#[non_exhaustive]`. For the ones the crate answers with, a `_` arm means:

| Enum | In a `_` arm |
| --- | --- |
| `EventKind` | An event the caller does not handle |
| `NextCheck` | Ask again shortly, as for `Soon` |
| `ActivityState`, `PromotionState` | A state the caller does not know; `PromotionState::Unknown` exists for the adapter's side |
| `ErrorCode` | An error without a way to recover |
| `Template`, `StartOutcome` | Read from a record or a driver; nothing to do |

The others a caller only gives. For them the attribute costs a `_` arm in a `match` that the caller
wrote over its own values.

A struct keeps public members and gains a constructor. `#[non_exhaustive]` on a struct would stop
both a literal and `..X::new(…)` outside the crate. With a constructor, code that names only what it
uses keeps compiling, and code that names every member still may.

What this covers is a member added to a struct and a variant added to an enum. A variant that has
fields, `EndPolicy::After { at }` or `EventKind::Action { action_id }`, keeps the fields it has: the
attribute is the enum's and does not reach into a variant, and one on the variant would stop a caller
from building it. What a later version adds comes as a member or as a variant.

`StartRequest::new` does not ask for the schema version and the template. Both follow from the
snapshot, and `validate` refuses a request where they do not. For a snapshot that implies no
template, one that shows nothing or two things, the constructor still answers a request, with
`Template::Standard`, and `validate` refuses it as it refuses the snapshot.

This breaks a `match` without a `_` arm over one of twelve enums, once. In a 0.x version the minor
number says so.

## Metrics below API 37

`MetricStyle` is of API 37. Below it the adapter showed the title and the status, and no metric.

- **Numbers and texts** become lines under the status, in a `BigTextStyle`: the label, the value, the
  unit. API 36 promotes a notification with that style, and not one with an inbox style. A number
  has the separators of the application's locale and is not rounded. A decimal is a float for
  Android: it is written with that float's own digits, 3.14 and not the 3.140000104904175 of the
  double nearest to it.
- **A stopwatch and a countdown** run. A text of one is wrong a second later, so they have no line.
  The first of them becomes the header clock, as a timer style's is. A second one is not shown.
- **A chronometer of the presentation** is applied after the style and is the clock instead.

From API 35 Android ignores a notification whose header clock is more than 14 days old, and the
adapter refuses such a post (LU-ADAPTER-10). A metric is not a clock the application asked for in the
header, and on API 37 it has no such limit. So a metric's clock that Android would not post is left
out, and the metrics are posted:

| | Refused when older than | Left out when older than |
| --- | --- | --- |
| A timer, a timer style, a chronometer | 14 days less one minute | — |
| A metric's clock below API 37 | — | 14 days less two minutes |

The renderer decides before the adapter checks, so its margin is the larger one: a clock it keeps
cannot be refused a moment later. A scheduled start is built as of its start time, where the clock
is as old as it will be.

## Android 8

The device suite of 0.6.0, run by hand on 2026-11-19 on an emulator of Android 8 (API 26, Google
APIs, x86_64):

| | Ran | Passed | Failed |
| --- | --- | --- | --- |
| The suite of 0.6.0, as it was | One test ended the run | | |
| The same without that test | 83 | 57 | 26: 10 assert a later Android, 7 call a method of a later Android in the test itself, 9 need the notification listener |
| With this change | 91 | 79 | 12, each at an assertion of a later Android: one of API 34, two of API 35, two of API 36, seven of API 37 |
| The same with the exact alarm allowed | 7 | 5 | 2 of the 12 again |

The test that ended the first run builds a notification with a context that names a package which is
not installed. Android 8 ends the process of an application whose notification has an icon it cannot
load; a later Android does not. The test asserts the same of the built notification now, and posts
nothing.

No test failed for what the adapter does on Android 8. The run without the notification permission
was not made: that permission is of API 33.

What the run changed is in the tests, not in the adapter: they read a notification's style from its
extras, ask Android for what it tells from API 31 only there, allow the notification listener through
the setting where the shell command is missing, and read a shell command's output to its end.
