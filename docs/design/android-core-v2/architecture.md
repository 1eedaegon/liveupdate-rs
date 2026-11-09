# Architecture

## Dependency direction

```text
TSX / React
    |
    v
Tauri compiler + Tauri IPC             native Rust app
    |                                      |
    +---------- Android presentation ------+
                         |
                         v
              liveupdate Rust domain core
       validation / lifecycle / ordering / receipts
                         |
                    bounded JSON/JNI
                         |
                         v
              Android AAR framework adapter
      styles / icons / PendingIntent / event storage
                         |
                         v
                 Android system UI / OEM
```

The Rust domain does not import Android classes. The Kotlin adapter does not decide revision
ordering, idempotency, fallback, or lifecycle. Framework observations and event batches travel back
through the same bounded bridge.

## Compatibility strategy

The existing structs and `schemaVersion: 1` wire shape stay readable. v2 adds optional fields with
serde defaults and requires a tagged presentation when `schemaVersion == 2`. Old records remain
valid; new fields are rejected on v1 so two meanings cannot share one schema version. This is a
pre-1.0 Rust-source change because public struct literals gain fields, but JSON compatibility and
stored v1 receipts are preserved.

## Ownership

| Concern | Owner |
| --- | --- |
| React conditions, bindings, platform projection | Tauri plugin |
| Presentation schema and validation | Rust core |
| Lifecycle, revisions, receipts, reconciliation | Rust core |
| Notification construction and API guards | Android AAR |
| Action/dismiss `PendingIntent` and durable event queue | Android AAR |
| Tauri permission dialog and JS event channel | Tauri plugin |
| Promotion and final visual appearance | Android/OEM system UI |

## Security boundary

The AAR accepts only explicit immutable `PendingIntent`s to non-exported receivers. Action IDs are
validated against the posted snapshot. Images resolve only from named app resources or canonical
paths under app-owned files/cache directories, with byte and dimension bounds. Caller-controlled
component names, URI grants, arbitrary intents, `RemoteViews`, and arbitrary extras are absent from
the contract.
