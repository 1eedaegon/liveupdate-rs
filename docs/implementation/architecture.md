# Architecture

## Boundary

Rust owns the public models, validation, lifecycle state, revision ordering, receipts and event
fan-out. The Android adapter owns only APIs that the framework must load from DEX: notification
construction, `PendingIntent`, broadcast receivers, permission/settings inspection and calls into
`NotificationManager`.

```text
consumer -> Rust core -> JNI boundary -> Android AAR -> framework/system UI
             ^                               |
             +----------- events ------------+
```

This keeps the product Rust-first without forcing Java framework types through generated bindings.
The AAR can be consumed by a native Rust application or by a framework plugin.

## Crates and modules

- `liveupdate`: platform-neutral contract and lifecycle.
- `android` AAR: managed Android adapter and manifest components.
- Android-specific Rust module: JNI initialization, bounded request/reply transport and callbacks.
- Test application: instrumentation and physical-device scenarios.

## JNI rules

Use a small number of bounded serialized entry points. Cache a `JavaVM` and global references,
never a thread-local `JNIEnv`. Validate every boundary value, check Java exceptions and map errors
to stable codes. Callbacks enter a thread-safe Rust event hub without holding lifecycle or JNI locks.

## Compatibility

Compile against the newest stable Android SDK while feature-detecting at runtime. A successful
notification post and a promotion request are separate outcomes. Older or unsupported devices use
the caller-selected fallback.
