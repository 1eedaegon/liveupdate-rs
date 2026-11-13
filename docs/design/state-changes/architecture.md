# Architecture

```text
host timer (liveactivity-rs session, a plugin, an application)
        |  at the time next_check named
        v
LiveUpdate::state_changes  -->  reconcile with Android  -->  compare with the state last answered
        |                                                       |
        +---- the records to report <---------------------------+---- receipts with the new answer
```

The core stays synchronous and without a clock. Whether a stale time has passed is still the adapter's
to say, when it observes. The core only names the time, in the snapshot's own milliseconds.

| Concern | Owner |
| --- | --- |
| When to wake up, and waking | Host |
| Whether a stale time has passed | Android adapter, at an observation |
| Which states were answered, and which are due | Rust core, in the receipt |
| Turning an answer into an event of the application | `liveactivity-rs` or the host |

The state last answered is a member of the stored receipt only. A record that is posted goes to the
adapter without it, so the adapter's strict record check and the record inside the notification are
what they were, and an AAR of 0.2 works with this crate.
