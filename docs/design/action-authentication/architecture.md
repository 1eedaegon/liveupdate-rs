# Architecture

```text
tap on an action
      │
      ├── API 31 and newer: Android asks to unlock, then sends the broadcast
      │
      └── below API 31: Android sends the broadcast at once
                                   │
                                   v
                 LiveUpdateActionReceiver: does the intent require authentication,
                 and is the device locked?  ── yes ──> nothing is stored
                                   │ no
                                   v
                 EventStore, then the application's listener
```

| Concern | Owner |
| --- | --- |
| Which action requires authentication | The application, in the snapshot |
| Carrying that to the tap | The renderer, in the action's intent |
| Asking for the unlock | Android, from API 31 |
| Holding a tap back below API 31 | The action receiver |

The receiver is the last place a tap passes before the application hears of it, whoever sent the
intent. The rule sits there and nowhere else.
