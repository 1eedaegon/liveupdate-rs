# Architecture

## Boundary

Rust owns the public models, validation, lifecycle state, revision ordering and receipts. The
Android adapter owns only what the framework must load from DEX: notification construction,
`PendingIntent`, permission and settings inspection, calls into `NotificationManager`, and the
`SharedPreferences` file that holds the receipts.

```text
application -> Rust lifecycle -> JSON over JNI -> Android AAR -> framework / system UI
                   ^                                   |
                   +------ active notifications -------+
```

The product stays Rust-first without forcing Java framework types through generated bindings. A
native Rust application or a framework plugin can use the same AAR.

## Parts

- `liveupdate` (`src/`): the contract, the lifecycle (`LiveUpdate`), the `Driver` trait, the JSON
  bridge (`JsonDriver`) and, on Android with the `android` feature, the JNI transport
  (`AndroidBridge`).
- The AAR (`android/`): `LiveUpdateBridge` (the JNI entry point), `LiveUpdateProtocol` (the
  dispatcher) and `AndroidAdapter` (the framework calls).
- The instrumentation tests (`android/src/androidTest`) with a Rust library of their own
  (`android/native-test`) that drives the crate through JNI.

## JNI

Each call is one bounded request and reply. The bridge keeps a `JavaVM` and global references,
never a thread-local `JNIEnv`, loads the AAR's class through the application's class loader, and maps
every failure, Java exceptions included, to a stable error code.

## Compatibility

The AAR compiles against the newest stable Android SDK and checks the API level at run time. A
successful post and a promotion are separate outcomes: older or unsupported devices get the fallback
the caller chose.

Events (taps on actions and dismissals) use a bounded durable drain; lifecycle dismissals are also
found by reconciliation instead.
