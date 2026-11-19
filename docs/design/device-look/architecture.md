# Architecture

```text
update / end (Rust, unchanged)
        │  record JSON
        v
AndroidAdapter.post ── NotificationRenderer.build ── the notification, with or without the Ongoing Activity
        │
        │  offered here, the new one has none, and the shown one has one?
        │        yes: cancel the shown one first
        v
NotificationManager.notify
        v
the watch: no notification with an Ongoing Activity, no icon
```

| Concern | Owner |
| --- | --- |
| Whether a notification carries the Ongoing Activity | `NotificationRenderer`, as before |
| Whether the shown notification has to go first | `AndroidAdapter.show`, where it posts |
| How a progress style is styled when nothing says so | `ProgressStyle::new`, in the crate |
| How a decimal is written below API 37 | `metricLines`, as API 37's style writes it |

The lifecycle does not change. It already waits for Android to list a post, so a notification that
is gone for a moment between the cancel and the post is not taken for one the user removed.
