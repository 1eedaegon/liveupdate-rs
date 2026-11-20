# Technical design

## Crate

- `src/presentation.rs`: `ActionTarget { kind, component, action }`, `TargetKind` (non-exhaustive),
  `ActionTarget::new`; `NotificationAction::target`, checked in `NotificationAction::validate`.
- `src/contract.rs`: `PresentationCapabilities::action_targets`.
- `src/client.rs`: start and update refuse an action with a target without the capability.
- `src/event.rs`: `EventKind::Handled { action_id }`, checked as an action's id.
- `src/android.rs`: `LiveUpdate::android_from_raw`, `AndroidBridge::from_raw`.

## AAR

- `Targets.kt`: the target's check, the component's resolution, and the PendingIntent.
- `NotificationRenderer.applyActions`: a target in place of the receiver's broadcast.
- `LiveUpdateActions.kt`: `LiveUpdateActions.take`, `LiveUpdateTap`, and `TargetTap` with the device's
  version and lock given, for the tests.
- `EventStore.appendHandled`, written with `apply()`; every append answers the event it stored.
- `LiveUpdateEvents`: `addNativeListener`, `removeNativeListener`, `LiveUpdateNativeListener`,
  `LiveUpdateEvent`; `stored(event)` tells both kinds of listener.
- `LiveUpdateForeground`: `attachOr`, `LiveUpdateFallback`; `attach`, `release` and `detach` know a fallback.
- `AndroidAdapter.capabilities`: `actionTargets`.

## Tests

Written first and seen failing.

1. Rust: a target's JSON and its checks, beside a link refused; the lifecycle refuses it without the
   capability and posts it with it; `handled` events and their checks; the new enum in the API tests.
2. Kotlin on the JVM: the rule of `take` for every version, lock and authentication.
3. Device: a target of each kind reaches a service or a receiver of the test package, with the ids, from a
   sent PendingIntent; the service's `take` records `handled`, which the bridge drains; the native
   listeners and the one listener are told; a component the package lacks and a target beside a link are
   refused; below API 31 a locked device withholds; an action without a target still goes to the receiver.
4. Device: `attachOr` with no activity, with one, at its end and after a later `attach`, heard by a
   notification listener: one notification at a time, the service in the foreground throughout.
5. Device: `android_from_raw` runs the lifecycle with `ndk-context` released.
6. By hand on the S23 and an emulator: the consumer application with a target service, a tap with the
   screen off and from the lock screen.
