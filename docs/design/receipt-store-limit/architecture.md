# Architecture

```text
start / update ──> deliver ──> do the live receipts fit? ── no ──> SYSTEM_LIMIT, nothing stored
                                        │ yes
                                        v
                    every live receipt, and the newest tombstones that fit beside them
                                        │
every store ─────> save ──> at most 128 tombstones ──> JsonDriver ──> adapter
                                                       1 MiB a call    1 MiB stored, a read in an envelope
```

| Concern | Owner |
| --- | --- |
| How many receipts are kept, and which go first | Rust lifecycle (`save`), as before |
| How many bytes a start or an update may store | Rust lifecycle (`deliver`): one limit, below what the bridge carries |
| What one call may carry | `JsonDriver` and the adapter, 1 MiB each way, unchanged |

The lifecycle has no knowledge of the transport except this one number and how the adapter writes a
`/`. That is as much as it knew before: it already bounds observations by what the adapter reports
(LU-LIMIT-4).

The limit is applied where content comes in, at a start and an update, by letting tombstones go.
Nothing else can fail for it: an end turns a live receipt into a tombstone, and a reconciliation
changes a few bytes of state, for which the limit leaves room.
