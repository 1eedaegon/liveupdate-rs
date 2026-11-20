# System design

## A target

```rust
NotificationAction {
    target: Some(ActionTarget {
        action: Some("dev.fremen.app.RECORD".into()),
        ..ActionTarget::new(TargetKind::ForegroundService, "dev.fremen.app.FremenService")
    }),
    ..NotificationAction::new("record", "Record")
}
```

In JSON, a member of the action: `"target": {"kind": "foregroundService", "component":
"dev.fremen.app.FremenService", "action": "dev.fremen.app.RECORD"}`, left out when there is none.

| Kind | PendingIntent | For |
| --- | --- | --- |
| `service` | `getService` | A service that runs already, or that Android lets the application start |
| `foregroundService` | `getForegroundService` | A service that may not run; it must call `startForeground` within Android's few seconds |
| `broadcast` | `getBroadcast` | A receiver of the application |

- **The component** is an ASCII class name of any Java package, in the application's package: the intent is
  `ComponentName(context.packageName, component)`. A Tauri application compiles classes of another Java
  package than its application id, and that is the case it must take. The component must not be exported,
  since any application could then send it the marks of a tap, and must run in the application's main
  process, whose view of the event store `take` writes: the adapter refuses either.
- **The intent action** is optional and lets one component tell its targets apart.
- **The extras** are those of a link, `io.github.1eedaegon.liveupdate.activityId` and `.actionId`, and
  `.authenticationRequired`. The intent's data is `liveupdate://target/<activity>/<action>`, and the request
  code and, from API 29, the identifier are those of a link, so that each activity's action has a
  PendingIntent of its own.
- **The checks.** In the crate: the kind is one of three; the component is two or more Java identifiers
  joined by dots, at most 256 characters; the action, when given, is printable ASCII without spaces, at most
  256 characters; a target and a link together are refused. In the adapter, the same again, and the
  component must be one that Android resolves in the application: a service for the first two kinds, a
  receiver for the third. A post that names one the application does not have fails with `INVALID_INPUT`,
  as a link that nothing opens does.
- **Authentication.** From API 31 Android asks for the unlock before it sends the PendingIntent, as for any
  action. Below it Android sends it at once, and `take` answers that the tap is withheld while the device is
  locked: the application must then not act.
- **The capability** `actionTargets`. An adapter of 0.7 leaves a target out, a member it does not know, and
  would send the tap to the queue. So the lifecycle refuses a start or an update whose actions name a target
  where the capability is false, with `UNSUPPORTED_FEATURE`.

## Taking a tap

`LiveUpdateActions.take(context, intent)` answers a `LiveUpdateTap` (`activityId`, `actionId`, `withheld`),
or null for an intent without the marks of a target.

1. It reads the marks and removes them from the intent, so that an intent delivered again is not counted
   twice.
2. Below API 31, for an action that requires authentication, by the intent or by the stored receipt (a
   receipt that cannot be read protects the action), and a locked device: it logs, records nothing and
   answers `withheld`.
3. For an activity that the stored receipts know, it appends a `handled` event with the action's id, and
   tells the one listener and the native listeners.
4. It never throws. A failed record is logged, and the tap is still answered: the application acts.

The `handled` event is written with `apply()`: the application took the tap already, and the event only
tells Rust and the page. The queue's other events keep `commit()`, which is how they are delivered.

In Rust, `EventKind::Handled { action_id }`. A crate of 0.7 reads it as `EventKind::Unknown` and does not
act on it; an earlier one fails the batch, as for any kind it does not know.

## Listeners that do not consume

`LiveUpdateEvents.addNativeListener(listener)`, `removeNativeListener(listener)`. A listener is told of
every event that is stored, with its content: `LiveUpdateEvent(sequence, activityId, occurredAt, kind,
actionId)`, where `kind` is `action`, `dismissed`, `opened` or `handled`. It runs on the thread that stored
the event, after the one listener; one that throws is logged and the others run. Listeners live in memory: a
process that a tap started has those that its application registered when it started, as a service does in
`onCreate`.

## Rust from a service

`LiveUpdate::android_from_raw(vm, context)` builds the client from the process's `JavaVM` and a context,
both raw JNI pointers, as `ndk-context` takes them, without the global that `LiveUpdate::android()` reads.
The bridge keeps a global reference to the context's application context. A service's JNI entry:

```rust
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_Native_record(mut env: EnvUnowned<'_>, _: JObject<'_>, context: JObject<'_>) {
    // SAFETY: the process VM, and a context that is valid for the call.
    let client = unsafe { LiveUpdate::android_from_raw(vm.get_raw().cast(), context.as_raw().cast()) };
    // start, update, end
}
```

## A foreground service

`LiveUpdateForeground.attachOr(service, type, fallbackId, fallback)`:

| The activity with the foreground placement | What the service shows |
| --- | --- |
| None, or its receipt cannot be read | The fallback, under `fallbackId`; answers false |
| One | Its notification, under `NOTIFICATION_ID`; answers true, and cancels a fallback that the service showed |
| It ends, or another service takes it | The fallback again, the service in the foreground throughout, and the activity's notification cancelled; the listener is told |

A service that called `attachOr` is never out of the foreground: a recording service of type microphone
cannot leave it while it records. `attach` after `attachOr` takes the fallback away the same way. `detach`
takes the service out of the foreground and forgets its fallback. A service that used only `attach` behaves
as before.

The fallback is built by the application on each `attachOr` and at the activity's end, outside the
library's lock: a builder that asks the application's Rust would otherwise wait for a lifecycle call that
waits for the lock. Each service has its own fallback, kept while it lives.
