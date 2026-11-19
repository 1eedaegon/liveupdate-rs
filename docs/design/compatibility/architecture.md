# Architecture

```text
the application
      │  a request: read strictly, a member that is not known is refused        (unchanged)
      v
Rust lifecycle ── JsonDriver ──────────────── the adapter (AAR) ──────────── Android
      │                │   a record, a payload:                │
      │                │   what the adapter knows is checked,  │  below API 37 a metric style is
      │                │   the rest is left alone              │  text and a header clock
      │                │                                       │
      │                │   a reply, an event:                  │
      │                │   what the crate knows is read,       │
      │                │   the rest is left alone              │
      v
the application's code: enums with a `_` arm, structs built on a constructor
```

| Boundary | Who reads | How |
| --- | --- | --- |
| Application → crate, a request in JSON | The request types | Strictly: an unknown member is the caller's mistake |
| Crate → AAR, a record and a payload | `NotificationRenderer`, `IconResolver`, `LiveUpdateProtocol` | Required members must be there, known members are checked, others are ignored |
| AAR → crate, a reply | `JsonDriver`, `Error`, `EventBatch` | Known members are read, others are ignored; an unknown event kind is `Unknown` |
| AAR → its own store, the event queue | `EventStore` | A stored event keeps what it has |
| Crate → its callers, the Rust API | The compiler | Open enums; constructors for what a caller builds |
| AAR → Android | `NotificationRenderer` | The system style where the device has it, otherwise the nearest thing it has |

## What must be understood

Leaving an unknown member alone is right for a member that adds something to what is shown. It is
wrong for one that the activity depends on: a start time that is ignored starts the activity now.

Such a member comes with a capability, and the lifecycle refuses the request where the adapter does
not report it. Three do so today: `startAt` (`scheduledStart`), `dismissAt` (`delayedEnd`) and the
foreground placement (`foregroundPlacement`). An adapter that does not know the capability leaves it
out, and a capability that is left out is false (LU-V2-CAP-2). So an earlier AAR never sees the
member.

A new member that guards something, as an action's `authenticationRequired` does, would be of this
kind.

## What stays strict

- A request that the application gives in JSON: `StartRequest`, `Snapshot` and what they hold.
- The values of members that are known. A style, a visibility or a state that the reader does not
  know is refused: nothing says what to show instead.
- What the lifecycle reads from its own store. It is made of the request types.
