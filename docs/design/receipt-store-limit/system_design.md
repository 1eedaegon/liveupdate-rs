# System design

## What the receipts take

The lifecycle writes the receipts as one JSON array. On their way and back they take:

| Where | Bytes | Bound |
| --- | --- | --- |
| The write request, `{"records":…}` | JSON + 12 | 1,048,576 (`JsonDriver`) |
| The adapter's store | its own text of the array | 1,048,576 (adapter) |
| The read reply, `{"status":"ok","data":…}` | stored text + 23 | 1,048,576 (`JsonDriver`) |

The adapter's text is not the lifecycle's: Android's JSON writes `/` as `\/`, one byte more for each,
and a decimal number, the progress of schema version 1 or a metric's value, can take a few bytes more
(`1e-7` is `1.0E-7`). A receipt has at most four of them.

So the measure of the receipts is their JSON with every `/` counted twice, and the limit is
**1,000,000** of those bytes.

## A start and an update

Content comes in at a start and an update, and only there. Before the pending receipt is stored,
`deliver` measures the receipts with it:

1. The live receipts alone over the limit: the call fails with `SYSTEM_LIMIT`. Nothing is stored and
   nothing is posted, as for the 33rd live activity. Ending an activity makes room.
2. Otherwise the tombstones are walked from the newest, and the walk stops at the first that does not
   fit beside the live ones. What stays is the newest run, so the oldest goes first, as LU-LIMIT-2 has
   it. The 128 still apply at the store.

## Every other store

Unchanged: the confirmation of a post, a reconciliation, an end, `state_changes`. None of them checks
the size, and none can fail for it. Between two starts or updates a receipt gains only

| What | Bytes at most |
| --- | --- |
| A state, a promotion state, the two flags | 9 |
| `dismissAt` of a delayed end | 29 |
| `reportedState`, on a receipt of an earlier version | 28 |

which is under 70 for a receipt and under 12,000 for 160 of them. The limit leaves 48,553 bytes below
the reply's bound: for those, for the envelope and for the decimal numbers, some 2,500 at most.

An end turns a live receipt into a tombstone of the same size, which the next start or update may let
go at once. What goes with a tombstone is what the 128 already took, only sooner with large content:

- Its end is not answered by `state_changes` when it was not asked before (LU-STATE-1).
- The last content of a delayed end can no longer be removed early: an end of that activity is
  `NOT_FOUND`. The notification leaves at its time, which Android keeps, not the lifecycle.

## The same start or update again

A start or an update whose post failed is posted again by the same call (`retry`), through `deliver`
and so through the same measure. Its receipt is stored already, so the live ones fit, unless the other
live receipts gained their few bytes since and the store was within those of the limit. The call is
then `SYSTEM_LIMIT` as any start that finds no room, and posts once another activity ended.

## Why not at every store

The first implementation measured at every store. It was correct, and it serialized all receipts a
second time for each of the three or four stores of a call: the host tests, which run in a debug
build, took 7 seconds instead of 1, and the mutation tests run them once for each of 485 mutants.
Measuring where content grows costs one serialization a start or an update.

## Receipts of 0.4.2

- Over the limit and under 1 MiB: read as before, and brought under the limit by the next start or
  update.
- Live receipts alone over the limit: a start and an update fail with `SYSTEM_LIMIT` until one ends.
  Every other call works.

## Requirements

`docs/spec.md`, "Limits": LU-LIMIT-5.
