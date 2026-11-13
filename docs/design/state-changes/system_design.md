# System design

## The stored receipt

`reportedState` joins the receipt: the state `state_changes` last answered for the activity.

| Value | Meaning |
| --- | --- |
| absent | A receipt of an earlier version. The first `state_changes` adopts its state without answering it |
| `unknown` | A new activity: nothing answered yet |
| `active`, `stale`, `ended`, `dismissed` | Answered |

## `state_changes()`

1. Reconcile, as every operation does: read, observe, cancel what an ended record still shows.
2. For each receipt: a state that is not `unknown` and differs from `reportedState` is answered, and
   becomes the `reportedState`. A receipt without one adopts its state.
3. Store the receipts when one changed, then answer the records, in the receipts' order.

A store that fails answers nothing: the same states are due at the next call. The end of an activity
whose tombstone storage dropped before it was asked for is not answered: storage keeps 128 of them. `unknown` is never
answered and never stored as answered, so an update that Android has not listed yet neither reports
nor hides anything: `active`, update, `active` again is silence; `stale`, update, `active` is one
answer.

## `next_check()`

Reads the receipts, without an observation:

- `Now` when a receipt has a known state that was not answered: another operation reconciled it.
- else `Soon` when a live receipt awaits Android's listing of a post that did not fail. A post that
  Android never lists keeps it, so a caller gives up after a few times.
- else `At(time)`: the earliest `staleAt` of the receipts that are `active`.
- else `Never`.

A receipt whose post failed needs its operation again, not time, so it asks for nothing. A time that
has passed means now. A caller whose check fails waits as for `Soon` before the next one.

## Operations and the answer

- `start` writes `reportedState: unknown`.
- `update`, `end` and reconciliation leave it alone.
- The adapter's `dismissed` event stays what it is: the prompt signal of a removal, where
  `state_changes` is the complete account of states. A layer above reports a removal from one of them.

## Requirements

`docs/spec.md`, "State changes": LU-STATE-1 to LU-STATE-6.
