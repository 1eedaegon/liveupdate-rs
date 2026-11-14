# Architecture

```text
start(start_at)                                  the time comes
      |                                                |
      v                                                v
Rust: receipt `scheduled` --write--> storage     AlarmManager --> LiveUpdateStartReceiver
      |                                 ^   |                           |
      +--schedule(record)--> adapter: check, arm        reads receipts, posts what is due,
                                                        notes what it posted (`starts`)
next lifecycle call
      |
      v
Rust: starts() --> adapter: post what is due, arm again, report --> observe --> `active`, `dismissed`
```

The receipts stay Rust's: the receiver reads them and never writes them. What the receiver did is kept
beside them, in a note of the adapter's own, and Rust reads it at its next call. That keeps one writer
for each of the two.

| Concern | Owner |
| --- | --- |
| Whether a start may be scheduled, its key, its state, what an update and an end mean | Rust core |
| Whether the content can be posted on this device | Adapter, when it is scheduled and again at the time |
| The time, the alarm, posting without the application | Adapter and its receiver |
| What became of a start: posted a moment ago, or gone | Adapter, asked by Rust before it observes |
| Turning `scheduled` and `active` into the application's events | `liveactivity-rs` or the host |
| The exact-alarm permission, and arming again after a restart | Host manifest |

The alarm is one, for the earliest scheduled receipt, and is always derived from storage. Scheduling,
an end, the alarm itself and every reconciliation arm it again, so a lost alarm heals at the next of
them.
