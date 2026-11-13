# Technical design

- `model`: `NextCheck { Soon, At(u64), Never }`.
- `driver`: `DriverRecord::reported_state: Option<ActivityState>`, left out of the JSON when absent.
- `client`: `state_changes` and `next_check` under the client's lock; `start` writes `unknown`; every
  record handed to `Driver::post` goes without the member.

## Tests

Integration tests against the fake driver, written first:

1. The states of one activity from start to end, each answered once.
2. `unknown` is not answered and does not hide the state after it.
3. A new client continues; a receipt without the member is adopted.
4. What is posted never has the member, what is stored has it.
5. A store that fails answers nothing and loses nothing.
6. `next_check` through an awaited post, two stale times, a failed post, ended activities, and its one
   driver call.

The fake driver records what it was given to post, and its observations can be made stale as the
adapter reports them after a stale time.

## Follow-up outside this crate

`liveactivity-rs` calls `state_changes` in `sync_events`, maps the answers to its state event, and gives
its host the time of `next_check`.
