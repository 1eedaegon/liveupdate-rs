# System design

## Operations

| Operation | Rust | Android adapter |
| --- | --- | --- |
| capabilities | Passes them on | Reads the API level, notification and promotion settings |
| start | Validates, checks the key, writes a pending receipt | Builds and posts the notification |
| update | Orders revisions, writes a pending receipt | Replaces the notification by its tag |
| end | Stores the terminal state | Cancels the notification |
| list | Reconciles receipts with observations | Reports its active notifications |

## Start

1. Rust validates the request and reads the capabilities.
2. Rust reconciles the stored receipts with Android's active notifications.
3. Rust writes a receipt marked `postFailed` before crossing JNI.
4. The adapter builds the notification from the receipt and posts it.
5. Rust marks the receipt posted and reconciles again; a failure leaves a receipt that the same start
   posts again. A post the adapter refuses as invalid leaves none: it would be refused again.

## Recovery

Android may kill the process while its notification stays visible. Each operation reconciles the
stored receipts with `NotificationManager.getActiveNotifications()`. The record embedded in a
notification holds only what is needed to match its receipt. Invalid or contradicting data fails
closed, and error messages do not repeat notification content.

## Concurrency

One client runs its operations one at a time. Android posts and cancels asynchronously: a receipt
waits for its revision to appear, and a cancelled notification may still be listed for a moment.
