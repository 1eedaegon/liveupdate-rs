# System design

## The way in

```
FirebaseMessagingService.onMessageReceived (the host's)
  └─ LiveUpdatePush.handle(context, message.data)          AAR, Kotlin
       ├─ no "liveupdate" member → false
       ├─ the host's library: System.loadLibrary(meta-data io.github.eedaegon.liveupdate.LIBRARY),
       │  or already loaded
       ├─ nativeHandle(context, payload) → outcome JSON     JNI symbol exported by the crate
       │    └─ LiveUpdate::android_from_raw(vm, context)    Rust: start / update / end under the
       │                                                    process-wide lock
       ├─ EventStore.appendPushed(outcome)                  one writer of the events, as before
       └─ true
```

`handle` runs on the caller's thread. FCM gives `onMessageReceived` a few seconds, and the push takes
one lifecycle call.

## The JNI symbol

`Java_io_github_eedaegon_liveupdate_LiveUpdatePush_nativeHandle`, in the crate, behind the feature
`push` (which needs `android`). A cdylib exports the `#[no_mangle]` functions of the crates it links. That
was measured on 2026-11-27: a dependency's function that the host never calls is exported with LTO, on
macOS and on Android arm64. The host writes no Rust for the push.

A library that the AAR cannot load, or that lacks the entry, a member that is not a payload, and a panic
are logged, and `handle` answers true, since the message was the library's. None of them names an
activity whose event it could be, so nothing is stored.

## One lock per process

`LiveUpdate::serialized` takes a process-wide mutex instead of the client's own. The app's retained
client and the client that a push builds then take turns, and so do clients that a host made by
mistake. The README's "one retained client per process" stays the advice: a client reconciles on its
first call.

The push and the app's code must run in the same process, as an action's target must. A service
declared in another process has its own lock and its own client, and loses receipts. The docs say so.

## The payload

The data map's member `liveupdate` is JSON:

| Command | Members |
| --- | --- |
| `start` | `"v": 1`, `"command": "start"`, `"request"`: a `StartRequest` |
| `update` | `"v": 1`, `"command": "update"`, `"activityKey"`, `"snapshot"` |
| `end` | `"v": 1`, `"command": "end"`, `"activityKey"`, `"policy"`: an `EndPolicy` |

An unknown `v` or command is refused with `INVALID_INPUT`. An unknown member is left alone. An update or
end names the live activity of its key, which is the one that is neither ended nor dismissed. Without
one, the push answers `NOT_FOUND`.

## Outcome and event

The native entry answers `{"command","activityKey","revision","id"?,"error"?}`, and the AAR stores it
as an event of kind `pushed`:

```json
{"kind":"pushed","command":"update","activityKey":"ride:42","revision":3,"code":"STALE_REVISION"}
```

`code` is absent when the push did what it asked. The event's activity id is the activity's id when
the push named one. It is empty when there was none: an update of an unknown key, or a start refused
before an id was made. `EventKind::Pushed` is new. A crate before it refuses an event with an empty id,
and loses the batch it is in. Only a host whose library has this version's entry stores such an event,
so this happens only where an earlier crate drains the same store: a downgrade over the application's
data, or a second library.

A start that an ended activity of its key began answers that activity and begins nothing. FCM may
deliver a message again, or after a later one. A start after its end would otherwise bring the activity
back. A new activity under the key is a request that differs from the ended one.

## The server's builder

`liveupdate::push::Payload` builds on every target and needs no feature:

- `Payload::start(request)`, `Payload::update(key, snapshot)`, `Payload::end(key, policy)`;
- `.to_data() -> Result<BTreeMap<String, String>>`. It validates as the lifecycle does: the request,
  the snapshot, the key. A payload whose map is over 4096 bytes of UTF-8 (FCM's limit for data) is
  `INVALID_INPUT`, with a message that says so.

Revisions are the server's: a push of revision 3 after the app's own revision 3 is `STALE_REVISION`, so
a server and an app do not both update one activity. The README says so.

## Capability

`push: true` from an AAR with `LiveUpdatePush`. It says what the AAR has, not what the host built: a
library without the feature `push` has no entry, and `handle` logs that. liveactivity-rs reads it to
answer `UNSUPPORTED_FEATURE` with an earlier AAR.
