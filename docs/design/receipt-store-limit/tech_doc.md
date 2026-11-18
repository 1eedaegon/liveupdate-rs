# Technical design

All of it is in `src/client.rs`:

- `STORE_LIMIT`: 1,000,000.
- `receipt_bytes(record)`: the bytes of its JSON, with one more for every `/`.
- `array_bytes(receipts, count)`: with the brackets and the commas of the array.
- `fitting(records, limit)`: `SYSTEM_LIMIT` when the live ones take more than `limit`; otherwise the
  live ones and the newest tombstones that fit beside them. `deliver` stores what it answers.

`save` is as it was. No type, no JSON member and no driver call is added.

## Tests

Written first and seen failing.

Unit tests of the rule (`src/client/tests.rs`), with a limit of a few receipts: the rule is the same at
any size.

1. Receipts of exactly the limit are all kept; one byte more and the oldest tombstone goes. The size
   is computed by the test as the adapter stores it, with `\/`. The same for tombstones alone.
2. A `/` counts twice: receipts whose JSON is the same to the byte and whose stored text is one byte
   more lose the oldest tombstone.
3. The tombstones that stay are the newest run: a small old one does not stay behind a large newer one
   that went.
4. Live receipts of exactly the limit have room, also beside a tombstone, which goes; one byte more
   has none.

Integration tests against the fake driver (`tests/reconcile.rs`), at the real limit:

5. Receipts as an earlier version left them, 33 tombstones of the largest text: a start that the
   adapter refuses puts all of them back; the next start succeeds, 29 of them stay, the newest, and
   one more would not have fit.
6. Thirty live activities of the largest text refuse the next start, and a smaller one that still
   fits refuses its larger update, each with `SYSTEM_LIMIT`, storing and posting nothing; after an end
   the update succeeds and the tombstone is gone.

A megabyte of receipts takes a debug build a tenth of a second to serialize, so these two tests seed
the fake driver instead of making thirty calls, and compute sizes from one receipt.

## A test that is not kept

Over the JSON bridge, against an adapter fake that stores as the AAR does, with `\/`, its 1 MiB bound
and the reply's envelope: receipts full of `/` as an earlier version left them, and one more start.
Seen failing with the adapter's `INVALID_INPUT` for the write, and passing with the limit. It parsed
megabytes of JSON in a debug build and took three seconds, three times the whole suite, so it is not in
the suite. The `/` is covered by test 2, and the bounds by the arithmetic in
[system_design.md](system_design.md).
