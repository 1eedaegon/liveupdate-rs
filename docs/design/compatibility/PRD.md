# Compatibility PRD

## Problem

Three parts meet in an application: the crate, the AAR, and the Android version of the device. Each
changes on its own, and each change could break one of the others.

**A crate and an AAR of two versions.** An application takes the crate from Cargo and the AAR as a
file, so the two are not always of one version.

- The AAR refuses a record that has a member it does not know. 0.6.0 showed it: a presentation that
  turns the watch's Ongoing Activity off is refused by every earlier AAR with `INVALID_INPUT`, the
  error of a request that is wrong. Twelve optional members came that way since schema version 2.
- The crate fails a reply that has a member it does not know in an event, a batch, an error or the
  envelope, and an event of a kind it does not know. A drain removes the events from the adapter
  before the crate reads them, so that batch is lost.
- The adapter's event queue refuses a stored event it does not know, and with it every later event
  and every drain, until the application's data is cleared.

**The Rust API.** A new member breaks every struct literal that names all members, and a new variant
every `match` without a `_` arm. Three of fifteen public enums are `#[non_exhaustive]`, and three of
the structs a caller builds have a constructor. 0.5.0 and 0.6.0 each added a member.

**Older Android.** The AAR runs from Android 8 (API 26).

- Below API 37 a metric style shows the title and the status. The metrics are not shown at all.
- The device suite runs on API 37 in CI. The lowest version it had run on was API 31, a phone. On
  2026-11-19 the suite of 0.6.0 ran on an Android 8 emulator: see [system_design.md](system_design.md).

## Goal

A newer crate, a newer AAR and an older Android each keep the other parts working, with less shown
where something is not known, and code written against this version compiles against the next.

## Required capabilities

- The adapter posts a record that has members it does not know, without them. What it knows, it
  checks as before.
- The adapter's dispatcher ignores members of a payload it does not know, and answers a command it
  does not know with `UNSUPPORTED_FEATURE`.
- The adapter's event queue keeps and hands on a stored event with members or a kind it does not know.
- The crate reads a reply with members it does not know, and an event of a kind it does not know as
  `EventKind::Unknown`.
- Every public enum is `#[non_exhaustive]`.
- Every struct a caller builds has a constructor that takes what is required, to build on.
- Below API 37 a metric style shows its metrics: numbers and text as lines, the first stopwatch or
  countdown as the header clock.
- A request from the application is read as strictly as before: a member that is not known there is
  a mistake of the caller, and is refused where it was written.

## Options considered

| Option | Why not |
| --- | --- |
| The crate leaves out what the AAR does not know | The record that is posted is the one that is stored, compared with the notification and with the same start again. Leaving a member out for one adapter changes all three |
| A contract number that the AAR reports | The capabilities already say, feature by feature, what an adapter does. A number would say it twice |
| The adapter refuses an unknown member with `UNSUPPORTED_FEATURE` | Still nothing is shown. An option that is not shown is the smaller loss |
| The crate reads a stored receipt with members it does not know | The request types refuse unknown members, for the application's sake, and a receipt is made of them. Reading one leniently takes a second set of types |
| `#[non_exhaustive]` on the structs | A struct literal with `..X::new(…)` stops compiling outside the crate. Constructors keep both ways |
| A lower `minSdk` | Android 7 has no notification channels: another path through the whole adapter. The owner keeps API 26 |
| A text for a stopwatch or a countdown | A time that does not run is wrong a second later |

## Success criteria

- An AAR of this version posts a record of a later crate, and a crate of this version reads the
  replies of a later AAR.
- Code that builds its requests on the constructors and matches with a `_` arm compiles when a member
  or a variant is added.
- The device tests pass on API 37, and on Android 8 but for those that assert a later Android.
- Tests are written and seen failing first; the host gates and the mutants of the changed lines pass.

## Non-goals

- An AAR up to 0.6.0. It refuses what it does not know, and stays as it is.
- An earlier crate over receipts that a later one stored: it does not read a receipt with a member it
  does not know (see the options).
- An unknown value of a member that is known, a style or a visibility: it is refused as before.
- Segments, points and icons of a progress style below API 36, and semantic colors below API 37.
