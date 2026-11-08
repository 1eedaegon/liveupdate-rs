# System design

## Operations

| Operation | Rust responsibility | Android responsibility |
| --- | --- | --- |
| capabilities | Normalize the result | Inspect SDK, notification and promotion settings |
| start | Validate, reserve key and revision | Build and post the notification |
| update | Serialize and reject stale revisions | Replace by stable tag and notification ID |
| end | Commit terminal state | Cancel or replace with final content |
| list | Reconcile persisted records | Report active notification identities |
| event | Fan out typed events | Receive actions, dismissal and settings changes |

## Start flow

1. Rust validates the request and reserves its activity key.
2. Rust records a pending receipt before crossing JNI.
3. Android checks capabilities, creates a system-template notification and posts it.
4. Android returns notification identity and the observed promotion state.
5. Rust commits the accepted record or retains a retryable failure receipt.

## Recovery

Android may kill the process while its notification remains visible. Initialization reconciles
persisted records with `NotificationManager.getActiveNotifications()`. Internal metadata is
bounded and contains only what is needed to match the Rust record. Invalid metadata fails closed
and error messages do not repeat notification content.

## Concurrency

Writes for one activity are serialized in Rust. Different activities can progress independently.
An update or end receives the next revision when its future begins execution. Android callbacks can
arrive on arbitrary threads and cannot hold a JNI reference beyond the call.
