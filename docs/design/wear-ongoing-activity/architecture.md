# Architecture

```text
start / update (Rust, unchanged)
        │  record JSON
        v
AndroidAdapter ── NotificationRenderer ── builds the platform notification, as before
        │
        │  a watch below API 37, and the application has the library?
        v
WearOngoing ── builds the Ongoing Activity from the built notification
        │      (small icon, content intent, title, text, clock) with androidx.wear.ongoing,
        │      and copies its data into the notification's extras
        v
NotificationManager / the host's foreground service
        │
        v
the watch: icon on the watch face, entry among the recent applications
```

| Concern | Owner |
| --- | --- |
| What the activity shows | The application, in the snapshot, as before |
| Whether the device and the application offer the Ongoing Activity | `AndroidAdapter`, once for each adapter |
| The Ongoing Activity's data | `androidx.wear:wear-ongoing`, which the application brings |
| Reading the notification into it | `WearOngoing`, the only class that names AndroidX |
| Showing it | The watch's system UI |

The AAR compiles against the library and does not bring it. `WearOngoing` is the one class whose code
names AndroidX classes, and nothing calls it before the adapter has found the library by name. An
application for phones never loads it.

The Rust lifecycle does not change: it learns of the surface through one capability.
