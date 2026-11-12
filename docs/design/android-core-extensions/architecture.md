# Architecture

## Dependency direction

```text
TSX / RSX / Rust builder
          |
          v
   liveactivity-rs                         native Rust app
 projection of a layout                          |
          |                                      |
          +--------- Android presentation -------+
                            |
                            v
                 liveupdate Rust domain core
      validation / lifecycle / ordering / receipts / placement
                            |
                       bounded JSON/JNI
                            |
                            v
                 Android AAR framework adapter  <----- host activity (hands an opening intent back)
   styles / links / channels / notification key <----- host foreground service (attach, detach)
                            |
                            v
                   Android system UI / OEM
```

The direction does not change. Two host components now call the AAR directly, in Kotlin: the activity
a link opens, and a foreground service the host owns. Neither decides a lifecycle: the activity only
reports an intent this crate built, and the service only holds a notification this crate built.

## Compatibility strategy

The schema version stays 2. Every extension is an optional member that is left out when unset, so a
request without it serializes as today and means the same. Schema 1 rejects all of them.

- An older AAR rejects a member it does not know (`INVALID_INPUT`, nothing posted): a crate and an AAR
  of different versions fail closed.
- Each extension has a capability, false unless the adapter reports it, so that a caller, or a host
  with a bridge of its own, can ask before it uses one. `PresentationCapabilities` therefore reads a
  missing member as false; today a missing member fails the whole reply.
- Two existing rules are relaxed, not replaced: a schema-2 update may change the style (LU-UPDATE-5,
  LU-V2-MODEL-1), and `EndPolicy::After` succeeds (LU-END-3). What they refused becomes accepted;
  nothing accepted today is refused.
- Rust sources change before 1.0: public structs gain fields and `EventKind` a variant.

## Ownership

| Concern | Owner |
| --- | --- |
| Layout, bindings, conditions, the projection onto a presentation | `liveactivity-rs` |
| Which screen a URL means | Application |
| URL syntax and refused schemes | Rust core, checked again in the AAR |
| The opening `PendingIntent`, its package restriction and its extras | Android AAR |
| Placement, the limit of one foreground activity, the dismissal time of an end | Rust core |
| The notification's key, channel, final form and public version | Android AAR |
| The service, its type, its permission, when it starts and stops | Host |
| Holding and releasing the foreground notification | Android AAR (`LiveUpdateForeground`), called by the host's service |
| Promotion, alert sound and what a lock screen reveals | Android / OEM system UI |

## Security boundary

- **Links.** The contract gains one caller-controlled string that reaches an intent. It is a bounded
  absolute URL whose scheme is not `intent`, `content`, `file`, `javascript`, `data` or `android-app`.
  The AAR opens it with `ACTION_VIEW`, the application's own package set, no URI permission flag, in an
  immutable activity `PendingIntent`. A URL no activity of the package handles fails the post. Component
  names, other packages, grants and extras stay outside the contract.
- **Foreground.** The AAR declares no service and no permission. Its new public entry takes a `Service`
  from the same process and makes it foreground with a notification built from a stored receipt, never
  from caller JSON.
- **Channels.** The alerting channel is created by the adapter with a fixed id and name; a caller
  cannot name a channel.
- **Events.** An `opened` event is recorded only from an intent that carries this crate's extras and
  names a stored activity; its ids pass the same checks as an action's.
