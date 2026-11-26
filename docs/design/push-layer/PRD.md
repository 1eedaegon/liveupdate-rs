# Push layer PRD

## Problem

A crate built on liveupdate can keep more than liveupdate's receipt for an activity. liveactivity's
layout activity is one example: it keeps the layout request and its latest snapshot beside the
receipt, and projects each snapshot into a notification by what the device offers. A `liveupdate`
payload applied directly would bypass that crate:

- The server would have to project without knowing the device.
- The crate's store would go stale.
- A pushed start would become an activity that the crate does not list and cannot end.

The liveactivity-rs session asked on 2026-11-27 for a generic way in.

## Goal

A data message can carry a member for a layer above liveupdate. The AAR hands that member to the layer's
own native entry, in the host's library, as it hands its own member to the crate's. liveupdate knows
nothing of the layer.

## Required capabilities

- **Routing.** `LiveUpdatePush.handle(context, data)` routes by the data's members:
  - A map with `liveupdate.layer` goes to the layer's native entry,
    `LiveUpdatePush.nativeLayer(context, member)`.
  - A map with both members goes to the layer alone: one message has one owner.
  - A map with neither still answers false.
- **The same handling as the crate's own member.**
  - The library is the one that the meta-data names.
  - The entry runs on the calling thread.
  - An answer with a `command` is stored as a `pushed` event.
  - An answer without a command, an `UnsatisfiedLinkError` and a throw are logged, and store nothing.
- **Rust.**
  - `push::outcome(command, activity_key, revision, result)` builds the answer in liveupdate's shape,
    so that a layer's answer and the crate's stay one shape.
  - `push::LAYER_KEY` names the member.
- **Capability.** `pushLayer` is true with an AAR that hands the layer's member over. `push` was already
  true from 0.10.0, whose AAR ignores the member.

## Success criteria

- A device test hands a layer's member to a native entry of the test library. It sees the answer stored
  as a `pushed` event, and a map with both members going to the layer alone.
- An answer whose key or revision the crate would refuse stores nothing. A library without the layer's
  entry stores nothing and answers true: read in the code, not run, since the tests' library has the entry.
- Tests are written and seen failing first.
