# Architecture

| Need | Crate | AAR |
| --- | --- | --- |
| A message's way in | JNI `nativeHandle` behind `push`, exported from the host's library | `LiveUpdatePush.handle(context, data)`; meta-data `io.github.eedaegon.liveupdate.LIBRARY` |
| The rules | The lifecycle as it is: `start`, `update` and `end` through a client from `android_from_raw` | — |
| One writer | A process-wide lock in `LiveUpdate::serialized` | The event store's lock, as before |
| What happened | `EventKind::Pushed { command, activity_key, revision, code }` | `EventStore.appendPushed` |
| The server's side | `push::Payload`, on every target | — |
| Is it there | `PresentationCapabilities::push` | capability `push` |

Nothing in the AAR depends on Firebase. The consumer test application gets a messaging service in a
source set that is built only when a `google-services.json` is given from outside the repository.
