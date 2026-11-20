# Architecture

```text
start / update (Rust)                         a tap on an action
   │  an action with a target                       │
   v                                                v
lifecycle: refused without `actionTargets`   no target ──> AAR's receiver ──> `action` event (as before)
   │                                         a link    ──> the application's activity ──> `opened` (as before)
   v                                         a target  ──> the application's service or receiver
NotificationRenderer                                           │
   builds the PendingIntent to the target                     LiveUpdateActions.take
   (explicit, immutable, the ids)                              │  lock rule below API 31
                                                               v
                                                         `handled` event ──> the one listener (Rust drains)
                                                                         └─> native listeners (in memory)

a service of the application
   ├─ LiveUpdateForeground.attachOr(service, type, fallbackId, fallback): in the foreground at once
   ├─ its JNI entry ──> LiveUpdate::android_from_raw(vm, context) ──> start / update / end
   └─ LiveUpdateForeground.attach: the activity's notification in place of the fallback
```

| Concern | Owner |
| --- | --- |
| Whether a request may name a target | The lifecycle, by the capability |
| Whether the target exists, and the PendingIntent to it | The renderer, as it does for a link |
| Acting on a tap | The application's component |
| Recording a tap that the application took | `LiveUpdateActions.take`, called by the component |
| Telling native code of an event | `LiveUpdateEvents`, beside the one listener |
| Having a notification while the service runs | `LiveUpdateForeground`, with the application's fallback |

A target generalizes the link of an action: a component of the application instead of an activity that a
URL resolves to, the same ids among the extras, and a call that records what the application took, as
`LiveUpdateEvents.opened` does for a link.
