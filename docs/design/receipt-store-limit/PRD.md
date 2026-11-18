# Receipt store limit PRD

## Problem

The lifecycle keeps a receipt for every live activity and for the newest 128 that ended or were
dismissed. A receipt holds the content twice: the request's and the latest. The bridge carries at most
1 MiB a call, and the adapter stores at most 1 MiB. Nothing made the receipts fit.

Found on 2026-11-19 with the crate of 0.4.2 over a JSON bridge that stores as the adapter does, one
activity started, updated and ended after the other:

- **The store fills and stays full.** Tombstones went by their number only. With large content the
  receipts pass 1 MiB long before the 128th: after 109 activities with a text of 1,500 Korean
  characters, after 91 with a progress bar of 32 segments and 32 points and three buttons with links,
  after 42 with a text of 4,000. Every later `start` failed with `NATIVE_ERROR`, for good: no call made
  room. The README's example takes 0.3 MB for 128 tombstones and never comes near.
- **A store that is written and cannot be read.** The adapter stores up to 1 MiB of receipts and
  answers a read inside an envelope of 23 bytes, and a reply over 1 MiB is refused. Receipts stored in
  those last bytes below 1 MiB were written and then failed every call that reads them, `list` and
  `end` too, until the application's data was cleared. Six of 300 text lengths tried for the last
  activity left such a store.

## Goal

What the lifecycle stores crosses the bridge both ways, whatever valid content the activities have,
and old receipts make room for new activities.

## Required capabilities

- A start and an update store the receipts in at most 1,000,000 bytes, counted as the adapter stores
  them.
- For that, tombstones go, the oldest first, until the rest fits. A live activity is never dropped for
  room.
- A start or an update for which the live activities alone leave no room fails with `SYSTEM_LIMIT`,
  and stores and posts nothing.
- An end and a reconciliation never fail for the size of the receipts.
- The AAR, the JSON of a receipt and the public API stay as they are: an AAR of 0.4.2 works with this
  crate.

## Options considered

| Option | Why not |
| --- | --- |
| Tombstones without their content | `list` answers the content of an ended record, and 128 large ones without the request's copy still do not fit |
| A method of `Driver` that says whether records fit | A new member of a public trait for one number. The bound is the lifecycle's, as 32 and 128 are |
| A lower write bound in the adapter, by the envelope | An AAR of 0.4.2 under this crate would still store what cannot be read. The lifecycle must not write it |
| A higher bound for a reply | It changes LU-BRIDGE-3 and moves the edge by 23 bytes |

## Success criteria

- With content of any valid size, a start succeeds as long as fewer than 32 activities are live and
  the live ones fit, however many ended before.
- No sequence of valid calls leaves receipts that a later call cannot read.
- Tests are written and seen failing first; the host gates and the mutants of the changed lines pass.

## Non-goals

- Reading a store that 0.4.2 left within 23 bytes of 1 MiB. Nothing is known to have one, and it
  needs the application's data cleared.
- Smaller receipts.
- A bound on what one activity's content may take: the model's limits stay as they are.
