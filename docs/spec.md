# Specification

What `liveupdate` and its Android AAR promise, one requirement per row. The tests that prove a
requirement name its ID in a comment directly above the test function (or in the function's name), and
the table at the end lists them. `tools/spec-coverage.sh` fails when a requirement has no test, a listed
test does not exist or does not name the requirement next to its definition, a test names a
requirement without being listed for it, or a test names an ID this file does not define.

Levels: **unit** (Rust tests inside `src/`), **integration** (Rust tests in `tests/`, with a fake driver
or a fake JSON bridge), **kotlin** (JVM unit tests of the AAR), **device** (instrumentation tests on an
emulator, `tests/android/run.sh`), **build** (shell checks of the built AAR and of CI) and **doc** (a
compiled doctest).

Platform says where the behavior applies: **all** is the Rust lifecycle with any driver; **Android**
is the AAR on a device; API levels are Android's.

Errors are `liveupdate::ErrorCode` values. Revisions and timestamps are integers in milliseconds:
`MAX_SAFE_INTEGER` is 9 007 199 254 740 991 and `MAX_TIMESTAMP` is 8 640 000 000 000 000.

## Client

| ID | Behavior | Input → expected result | Errors | Platform |
| --- | --- | --- | --- | --- |
| LU-CLIENT-1 | `LiveUpdate<D>` is `Send + Sync`, and its operations run one at a time: each holds the client's lock from its first driver call to its last. | Eight threads start the same request → one activity, one post. Eight threads each start, update and end their own key → eight ended records. | — | all |
| LU-CLIENT-2 | A driver that panics does not disable the client. The lock guards no data, and every operation reconciles the durable receipts, so the next operation runs normally. | `capabilities` panics once → the next `capabilities` and `start` succeed. | — | all |

## Start

| ID | Behavior | Input → expected result | Errors | Platform |
| --- | --- | --- | --- | --- |
| LU-START-1 | `start(request)` checks the request, reads the capabilities and reconciles. For a new key it writes a receipt with a new UUID v4 `id` and `postFailed: true`, posts it, then marks it posted and reconciles again. The Android-only record has the request's key, schema version, template and snapshot, no cross-platform backend discriminator, and `visibility: unknown`; `state` is `active` once Android lists the notification and `unknown` before. | A valid request → driver calls capabilities, read, observe, write (`postFailed: true`), post, observe, write, read; an `active` record. | see LU-START-6 | all |
| LU-START-2 | While the activity of an `activityKey` is not ended or dismissed, the same request (every field equal) returns that activity without posting again. | The same start twice → one id, one post. | — | all |
| LU-START-3 | While the activity of a key is live, any other request under that key fails and posts nothing. | Another title, fallback, visibility or template → error. | `Conflict` | all |
| LU-START-4 | Once the activity of a key ended or was dismissed, a start under that key makes a new activity with a new id. The old id stays unknown to updates. | Start, end, start → two ids; an update of the first → error. | `NotFound` (old id) | all |
| LU-START-5 | A post that fails, other than by a refusal as invalid (LU-START-8), leaves its receipt with `postFailed: true`. The same start posts it again under the same id; another request under the key conflicts. | The post fails once → error; the same start → the same id, `active`, two posts. | `NativeError` (the failed post), `Conflict` | all |
| LU-START-6 | A start checks, in this order: the request, the notification permission, the stored records and observations, an existing activity of the key (its record, or a conflict), the fallback, the foreground placement (LU-FGS-2), the limit of live activities. So the same start of a live activity returns it at the limit or without promotion, but not without notification permission. | Notifications off and an invalid key → `InvalidInput`; notifications off and the same start → `PermissionDenied`; 32 live and the same start → its record; a new key without promotion and `Fallback::None` at the limit → `UnsupportedFeature`. | `InvalidInput`, `PermissionDenied`, `NativeError`, `Conflict`, `UnsupportedFeature`, `SystemLimit` | all |
| LU-START-7 | Receipts are durable: a new client, as after a process restart, finds the activity of a key, updates it and lists it. | Client A starts; client B starts the same request → the same id. | — | all; on Android through SharedPreferences |
| LU-START-8 | The adapter refuses a post as invalid before it posts anything, and would refuse it again. A start that is refused so leaves no receipt: the key, the foreground placement and the limit are free, and a corrected start succeeds. A start, or the same update of one, that is retried after another failure and is then refused is removed as well, after a cancel for the notification that the earlier post may have left; a cancel that fails keeps the receipt. An activity that was updated since keeps its receipt: the content before a refused update is not known then. When the receipts cannot be stored back, that failure is the answer and the same operation meets the refusal again. | A URL nothing opens → error, `list` is empty; the corrected start → `active`. | `InvalidInput` | all |

## Update

| ID | Behavior | Input → expected result | Errors | Platform |
| --- | --- | --- | --- | --- |
| LU-UPDATE-1 | `update(id, snapshot)` with a higher revision stores and posts the snapshot and returns the record with it. The start's request stays the record's origin. | Revision 0, update with 5 → the record at 5, `active`, two posts. | see LU-UPDATE-2 to LU-UPDATE-7 | all |
| LU-UPDATE-2 | A lower revision than the accepted one fails and posts nothing. | At 2, an update with 1 → error. | `StaleRevision` | all |
| LU-UPDATE-3 | The accepted revision with the same content returns the record without posting. When that revision's post had failed, it posts again. | The same update twice → one post; after a failed post the same update → posted, `active`. | — | all |
| LU-UPDATE-4 | The accepted revision with other content fails. | At 2 with progress 0.5, revision 2 with 0.7 → error. | `Conflict` | all |
| LU-UPDATE-5 | A snapshot keeps the schema generation of its start, and a schema-1 snapshot keeps the start's template. A schema-2 snapshot may carry another style (LU-V2-MODEL-3). | A progress activity, a timer snapshot → error; a schema-1 snapshot for a schema-2 activity, or the reverse → error. | `InvalidInput` | all |
| LU-UPDATE-6 | Only live activities update: an unknown, ended or dismissed id fails. | After the user removed the notification → error. | `NotFound`; `InvalidInput` for an invalid id (LU-VALID-10) | all |
| LU-UPDATE-7 | A newer revision needs notification permission; the same-revision answer of LU-UPDATE-3 does not. An update decides neither the fallback nor promotion again: `promotionRequested` stays what the start chose. | Notifications off → error; promotion turned off after the start → still requested. | `PermissionDenied` | all |
| LU-UPDATE-8 | Revisions may skip numbers. | 0, then 5 → accepted. | — | all |
| LU-UPDATE-9 | An update whose post the adapter refuses as invalid returns the receipt to the snapshot accepted before it. The record is then what Android still shows, and the same revision can carry corrected content. | At 0, update 4 is refused → error, still at 0 and `active`; update 4 with other content → accepted. | `InvalidInput` | all |

## End

| ID | Behavior | Input → expected result | Errors | Platform |
| --- | --- | --- | --- | --- |
| LU-END-1 | `end(id, EndPolicy::Immediate)` and `end(id, EndPolicy::Default)` store the record as `ended`, no longer awaiting a post (`awaitingObservation` and `postFailed` false), then cancel the notification, and return the ended record. `Default` is immediate on Android. | → driver calls read, observe, write, cancel; the notification is gone. | — | all |
| LU-END-2 | Ending an ended activity returns its record and does nothing else. | A second end → `ended`, no second cancel. | — | all |
| LU-END-3 | `EndPolicy::After { at }` needs an adapter that reports `delayedEnd` (LU-END-7): without it the end fails after looking the id up, and the activity stays. An `at` above `MAX_TIMESTAMP` fails before the lookup. | `After { at: MAX_TIMESTAMP }` → error, still `active`; one more → `InvalidInput`; an unknown id → `NotFound`. | `UnsupportedFeature`, `InvalidInput`, `NotFound` | all |
| LU-END-4 | Ending a dismissed activity marks it `ended`. | Removed by the user, then ended → `ended`. | — | all |
| LU-END-5 | When the cancel fails, the record stays `ended`, and the next operation's reconciliation cancels the notification Android still shows (LU-LIST-7). | The cancel fails → error; the next `list` → cancelled. | `NativeError` | all |
| LU-END-6 | An unknown id fails, and so does an invalid one. | — | `NotFound`; `InvalidInput` (LU-VALID-10) | all |

## List and reconciliation

| ID | Behavior | Input → expected result | Errors | Platform |
| --- | --- | --- | --- | --- |
| LU-LIST-1 | `list()` reconciles and returns every kept record: live ones first in the order they started, then tombstones from the oldest to the newest. | Live, ended, dismissed → those three, in that order. | — | all |
| LU-LIST-2 | A listed notification sets `state` to `active`, or `stale` once its `staleAt` has passed on the device; it sets the promotion state Android reports, and clears `awaitingObservation` and `postFailed`. | Listed `stale` and `promoted` → the record says so. | — | all |
| LU-LIST-3 | A record Android showed (`active` or `stale`) whose notification is no longer listed becomes `dismissed`, a tombstone. Its updates fail. | The user removes the notification → `dismissed`. | `NotFound` (update) | all |
| LU-LIST-4 | A record Android has not listed yet stays `unknown`, not dismissed, and can still be updated. | A post Android shows later → `unknown` across lists. | — | all |
| LU-LIST-5 | While a record awaits observation, Android listing an older revision keeps the accepted record. | Update to 1 while 0 is listed → the record keeps 1, `unknown`. | — | all |
| LU-LIST-6 | An observation that contradicts its receipt fails the operation and changes nothing: other content at a revision the receipt does not await, another template, another promotion request, or a promotion state that does not fit the request (`notApplicable` exactly when not requested). So does an observation the adapter cannot read, and a failed observe call. | Revision 99 listed → error, storage unchanged. | `NativeError` | all |
| LU-LIST-7 | A notification that Android still lists for an ended or dismissed record is cancelled. The last content of a delayed end is not listed (LU-END-9), so it stays. | An ended activity's notification comes back → cancelled, still `ended`. | — | all |
| LU-LIST-8 | Notifications without a receipt are left alone: neither listed nor cancelled. | — | — | all |
| LU-LIST-9 | A list writes storage only when reconciliation changed a record. | Two lists → no write; a dismissal → one write. | — | all |
| LU-LIST-10 | A stored receipt whose snapshot is of the other schema generation is still read: an earlier release wrote one for an update that mixed the generations, before the adapter refused its post. Such an update is refused now (LU-UPDATE-5), and the next update that fits replaces the receipt. A schema-1 receipt of another template is refused as before. | A schema-1 activity with a stored schema-2 snapshot → `list` succeeds. | `NativeError` (another template) | all |

## State changes

`docs/design/state-changes`. The core has no clock and no thread: it answers which states are due and
when to ask again, and the caller owns the timer.

| ID | Behavior | Input → expected result | Errors | Platform |
| --- | --- | --- | --- | --- |
| LU-STATE-1 | `state_changes()` reconciles and answers the records whose state is another than the one it last answered for them, each once, in the order `list` has them: `active` once Android lists the activity, `stale` once its stale time has passed, `active` again after an update, `ended`, `dismissed`. A state that was never asked for in between is skipped, and so is the end of an activity whose receipt storage dropped before it was asked for (LU-LIMIT-2). | Start, ask → `active`; ask → nothing; the stale time passes, ask → `stale`; update, ask → `active`; end, ask → `ended`. | — | all |
| LU-STATE-2 | `unknown` is never answered and never taken as answered. A post that Android has not listed yet neither reports nor hides a state: `active`, an awaited update, `active` again is silence; `stale`, an awaited update, `active` is one answer. | A start Android lists late → nothing, then `active`. | — | all |
| LU-STATE-3 | The state last answered is part of the stored receipt (`reportedState`), so a new client continues. A new activity has `unknown`, nothing answered. A receipt of an earlier version, without the member, is adopted with its state, unanswered; its next state is answered. | Client A asks, client B asks → nothing. | — | all |
| LU-STATE-4 | A record that is posted goes to the driver without `reportedState`, also the last content of a delayed end: the adapter's record check and the record inside the notification are what they were. Updates and ends leave the stored member alone. | Three posts → none has the member; the receipt has it. | — | all |
| LU-STATE-5 | When the receipts cannot be stored, `state_changes()` fails and answers nothing: the same states are due at the next call. With nothing due it stores nothing. | The store fails → error; the next call → `active`. | `NativeError` | all |
| LU-STATE-6 | `next_check()` reads the receipts, with one driver call, and answers when to call `state_changes()` next: `Now` when a receipt has a state that an operation already reconciled and that was not answered; else `Soon` while a live receipt awaits Android's listing of a post that did not fail; else `At(time)`, the earliest `staleAt` of the receipts that are `active`; else `Never`. Answered stale, ended and dismissed activities, and one whose post failed, ask for nothing. A post that Android never lists keeps `Soon`: a caller gives up after a few times. | Stale times 5000 and 3000 → `At(3000)`; the earlier one is stale → `At(5000)`. | — | all |

## Limits

| ID | Behavior | Input → expected result | Errors | Platform |
| --- | --- | --- | --- | --- |
| LU-LIMIT-1 | At most 32 activities are live (neither ended nor dismissed). A start of a new key beyond that fails and posts nothing; the same start of a live activity still returns it. | The 33rd key → error. | `SystemLimit` | all |
| LU-LIMIT-2 | At most 128 tombstones are kept, and the oldest goes first. A record that was just ended, or just found dismissed, is the newest. | 128 ended, then one dismissed and one ended → the newest 126 of the 128, then those two. | — | all |
| LU-LIMIT-3 | Stored records that break a bound fail closed: more than 32 live or 128 tombstones, a duplicate or invalid id, an invalid request or snapshot, a snapshot of another template. The adapter neither writes nor reads more than 160 records or 1 MiB. | 33 live records in storage → error, nothing written. | `NativeError`; the adapter's `INVALID_INPUT` (write) and `NATIVE_ERROR` (read) | all; Android |
| LU-LIMIT-4 | At most 160 observations are read, as many as records can be kept and as many as the adapter reports: the adapter fails on its 161st own notification. A notification Android still lists after its cancel, which it handles asynchronously, does not fail the 32nd live activity. | 32 live and one just ended still listed → the next start succeeds; 161 observations → error. | `NativeError`; the adapter's `NATIVE_ERROR` | all; Android |

## Capabilities and promotion

| ID | Behavior | Input → expected result | Errors | Platform |
| --- | --- | --- | --- | --- |
| LU-CAP-1 | `capabilities()` returns the driver's `{supported, notificationsEnabled, promotionEnabled}` unchanged. `can_post()` is `notificationsEnabled`; `can_promote()` needs all three. | — | — | all |
| LU-CAP-2 | The adapter reports `supported` when the API is 36 or later; `notificationsEnabled` when the app's notifications are on and its channel is not blocked; `promotionEnabled` when the API is 36 or later, `canPostPromotedNotifications()` is true and the channel's importance is above `IMPORTANCE_MIN`. | API 37, permission granted → `supported`, `notificationsEnabled`, and `promotionEnabled` = `canPostPromotedNotifications()`. | — | Android |
| LU-CAP-3 | `capabilities` and `post` create the channel `liveupdate-rs.v1`, named "Live updates", at `IMPORTANCE_LOW`. Doing it again changes nothing. Only a revision that alerts creates a second channel (LU-V2-ALERT-2). | — | — | Android |
| LU-CAP-4 | A start requests promotion exactly when `can_promote()`. The record says `promotionRequested`. `promotionState` is `unknown` after each post until Android lists it, then `promoted` or `notPromoted` after Android's `FLAG_PROMOTED_ONGOING`; it is `notApplicable` when promotion was not requested. `degradedFeatures` is `["nativeLiveActivity"]` when promotion was not requested or Android did not promote. | Promotion available, Android does not promote → `notPromoted`, degraded. | — | all; promotion on API 36+ |
| LU-CAP-5 | A promotion state this version does not know reads as `unknown`. Visibility and fallback are closed sets. | `"demoted"` → `Unknown`; visibility `"hidden"` → error. | — | all |

## Fallback

| ID | Behavior | Input → expected result | Errors | Platform |
| --- | --- | --- | --- | --- |
| LU-FALLBACK-1 | Without promotion (`can_promote()` false), `Fallback::Notification` posts a regular ongoing notification with the same template: `promotionRequested: false`, `notApplicable`, degraded. | API 35, or promotion turned off → posted, not requested. | — | all |
| LU-FALLBACK-2 | Without promotion, `Fallback::None` fails before anything is written or posted. | — | `UnsupportedFeature` | all |
| LU-FALLBACK-3 | With promotion available, both fallbacks request it. That Android then does not promote is reported in the record, not as an error. | — | — | all |

## Validation

| ID | Behavior | Input → expected result | Errors | Platform |
| --- | --- | --- | --- | --- |
| LU-VALID-1 | `activityKey` has 1 to 128 characters: the first an ASCII letter or digit, the others ASCII letters, digits, `.`, `_`, `:` or `-`. | `delivery:order-123` → valid; `-a`, `a b`, `é`, 129 characters → error. | `InvalidInput` | all |
| LU-VALID-2 | `schemaVersion` is 1 or 2. v1 is the legacy progress/timer shape; v2 is the Android presentation shape below. | 0 or 3 → error. | `InvalidInput` | all |
| LU-VALID-3 | `visibility` is `public`, `private` or `secret`; `unknown` is only reported. | `Visibility::Unknown` → error. | `InvalidInput` | all |
| LU-VALID-4 | `title` has 1 to 120 Unicode scalar values and `status` 1 to 240, and neither is only whitespace: U+0009 to U+000D, U+0020, U+00A0, U+1680, U+2000 to U+200A, U+2028, U+2029, U+202F, U+205F, U+3000, U+FEFF. | 120 Hangul syllables → valid; 121 → error; `"\u{3000}"` → error. | `InvalidInput` | all |
| LU-VALID-5 | A schema-v1 snapshot has exactly one of `progress` and `timer`, and a start's `template` matches it. Schema v2 instead has exactly one rich `presentation`. | Mixed, missing, or template mismatch → error. | `InvalidInput` | all |
| LU-VALID-6 | `progress` is finite and from 0 to 1. | 1.0000001, NaN, infinity → error. | `InvalidInput` | all |
| LU-VALID-7 | A `timer` starts before it ends, and ends at `MAX_TIMESTAMP` or earlier. | `startedAt == endsAt` → error. | `InvalidInput` | all |
| LU-VALID-8 | `revision` is at most `MAX_SAFE_INTEGER`; `staleAt` and `EndPolicy::After { at }` are at most `MAX_TIMESTAMP`. | One more → error. | `InvalidInput` | all |
| LU-VALID-9 | Requests read from JSON reject unknown members: `StartRequest`, `Snapshot`, `Timer` and `EndPolicy`. Names are camelCase; an end policy is `{"kind": "immediate" \| "default" \| "after", "at"?}`. | A snapshot with `"color"` → error. | serde error | all |
| LU-VALID-10 | An activity id has 1 to 256 Unicode scalar values and is not only contract whitespace at every Rust/Kotlin boundary. | `""`, `" \t"`, 257 scalars → error; 256 emoji → valid. | `InvalidInput` | all |
| LU-VALID-11 | Every check above runs before the first driver call. | An invalid start, update or end → no driver call. | `InvalidInput` | all |
| LU-VALID-12 | The adapter checks a post again before it builds the notification: the id, title, status, template, the progress range, the timer's order and a start at or after 1970, and the visibility. A failed check posts nothing. | A blank title, progress 1.5 → error, no notification. | `INVALID_INPUT` | Android |

## Errors

| ID | Behavior | Input → expected result | Errors | Platform |
| --- | --- | --- | --- | --- |
| LU-ERR-1 | `ErrorCode` has eight codes, in JSON `INVALID_INPUT`, `CONFLICT`, `NOT_FOUND`, `STALE_REVISION`, `PERMISSION_DENIED`, `UNSUPPORTED_FEATURE`, `SYSTEM_LIMIT` and `NATIVE_ERROR`. `Error` is `{code, message}` and nothing else, and displays as `Code: message`. | `{"code":"CONFLICT","message":"m","cause":"x"}` → not read. | — | all |
| LU-ERR-2 | The library's messages are fixed sentences: none repeats a title or a status. | A conflict about a title "Secret title" → a message without "Secret". | — | all |
| LU-ERR-3 | An adapter error keeps its code and message. An unexpected exception in the adapter, or a platform method or class that the device lacks (a `LinkageError`), becomes `NATIVE_ERROR` with the fixed message "The Android live update adapter failed.", and its cause stays on the device. | `PERMISSION_DENIED` from the adapter → `PermissionDenied`; a parser failure → `NATIVE_ERROR`. | — | all; Android |
| LU-ERR-4 | The adapter's `AdapterFailure` takes only the eight codes. | `TIMEOUT` → `IllegalArgumentException`. | — | Android |

## JSON bridge

| ID | Behavior | Input → expected result | Errors | Platform |
| --- | --- | --- | --- | --- |
| LU-BRIDGE-1 | `JsonDriver` turns the seven driver calls into `capabilities`, `read`, `write`, `observe`, `post`, `cancel` and `events`, with bounded JSON payloads. Records, observations and events use camelCase names. A client over a JSON adapter runs the lifecycle and event drain. | — | — | all |
| LU-BRIDGE-2 | A request over 1 MiB (1 048 576 bytes of JSON) fails without calling the bridge. | — | `NativeError` | all |
| LU-BRIDGE-3 | A reply over 1 MiB fails; one of exactly 1 MiB is read. | — | `NativeError` | all |
| LU-BRIDGE-4 | A reply is `{"status":"ok","data":...}` or `{"status":"error","error":{"code":...,"message":...}}`. Anything else fails closed: another status, a missing or mistyped `data`, more members in the envelope or the error, an unknown code, text that is not JSON. Objects inside `data` may gain members. | `{"status":"ok"}` → error; capabilities with an extra flag → read. | `NativeError` | all |
| LU-BRIDGE-5 | An error of `Bridge::invoke` itself is returned unchanged. `JsonDriver::into_inner` gives the bridge back. | — | the bridge's | all |
| LU-BRIDGE-6 | The adapter's dispatcher answers every call with an envelope. An unknown command, other members than the command takes, a payload over 1 MiB of UTF-8, more than 160 records, or an id that is blank or longer than 256 Unicode scalar values → `INVALID_INPUT`; a payload that is not a JSON object, or members of the wrong type → `NATIVE_ERROR`. `LiveUpdateBridge.invoke` hands the adapter `context.applicationContext`. | `{"extra":true}` for `read` → `INVALID_INPUT`. | `INVALID_INPUT`, `NATIVE_ERROR` | Android |
| LU-BRIDGE-7 | The adapter's storage refuses a write of more than 160 records or 1 MiB (`INVALID_INPUT`, nothing stored), and fails a read of a stored value that is not a JSON array, has more than 160 records or more than 1 MiB (`NATIVE_ERROR`). | — | `INVALID_INPUT`, `NATIVE_ERROR` | Android |

## Android adapter

| ID | Behavior | Input → expected result | Errors | Platform |
| --- | --- | --- | --- | --- |
| LU-ADAPTER-1 | A post shows one notification per activity: tag `liveupdate-rs:<id>`, notification id 1 (a foreground placement is keyed as LU-FGS-3 says), the channel of LU-CAP-3, the small icon `liveupdate_status` (unless the presentation names another, LU-V2-BRAND-2), the title and the status, category `progress`, ongoing, alerting only once, the visibility of the request, and the record's JSON in its extras. | — | — | Android |
| LU-ADAPTER-2 | A progress snapshot shows a determinate bar of 1000 steps at round(progress × 1000), and no time. | 0.4567 → 457 of 1000. | — | Android |
| LU-ADAPTER-3 | A timer snapshot shows a countdown chronometer to `endsAt`. | `when` = `endsAt`, counting down. | — | Android |
| LU-ADAPTER-4 | Tapping the notification opens the app's launch activity through an immutable `PendingIntent` that is distinct for each activity and carries its id; an app without a launch activity gets no content intent. A presentation's `openUrl` replaces it (LU-V2-LINK-2). | — | — | Android |
| LU-ADAPTER-5 | A post sets `EXTRA_REQUEST_PROMOTED_ONGOING` only when the record requests promotion and the API is 36 or later. | — | — | Android |
| LU-ADAPTER-6 | Observation lists only the adapter's own notifications: id 1 under the tag prefix, and the foreground placement's (LU-FGS-3). Each tagged one must carry record JSON whose id matches its tag, or the whole observation fails. The last content of an ended activity is not listed (LU-END-9). The state is `stale` when `staleAt` is at or before the device's clock, else `active`; the promotion state comes from `FLAG_PROMOTED_ONGOING`. | A notification under the prefix without the record → error. | `NATIVE_ERROR` | Android |
| LU-ADAPTER-7 | A post while the app's notifications are off fails. | Permission revoked → error, no notification. | `PERMISSION_DENIED` | Android |
| LU-ADAPTER-8 | Cancel removes the notification of that id only. An id never posted is fine; a blank one is not. | — | `INVALID_INPUT` (blank id) | Android |
| LU-ADAPTER-9 | Records live in the app's SharedPreferences file `io.github.1eedaegon.liveupdate` under `records`, written with a synchronous commit. Empty storage reads as `[]`. Each call blocks its thread for the disk write and the framework call. | — | — | Android |

## JNI

| ID | Behavior | Input → expected result | Errors | Platform |
| --- | --- | --- | --- | --- |
| LU-JNI-1 | `LiveUpdate::android()` (`AndroidBridge::new()`) takes the context of `ndk-context`, keeps its application context as a global reference, and loads `io.github.eedaegon.liveupdate.LiveUpdateBridge` through that context's class loader, so threads that Rust created find the AAR's class. | A Rust thread → the lifecycle runs. | — | Android |
| LU-JNI-2 | Every call attaches its thread when it is not attached; a Rust thread then stays attached until it exits. Calls work from Java threads and from Rust threads, and one client shared by many Rust threads serializes their calls. | Eight Rust threads with one client → eight ended activities; eight starts of one key → one id. | — | Android |
| LU-JNI-3 | A call frees its local references when it returns: hundreds of calls in a row on one thread keep working. | 100 rounds of list, capabilities and update → done. | — | Android |
| LU-JNI-4 | Text crosses JNI unchanged both ways, and so do the largest revision and timestamp: emoji, CJK, quotes, backslashes, U+0000 and U+2028; the notification shows the same title. | The title `🍡 "Mochi" 中文 \ / U+0000 é` → listed and shown unchanged. | — | Android |
| LU-JNI-5 | A Java exception during a call becomes `NativeError`, and the thread can keep calling. | A context whose application context is null makes the adapter's constructor throw → error. | `NativeError` | Android |
| LU-JNI-6 | Without `ndk-context`, `LiveUpdate::android()` returns an error instead of panicking, and succeeds once the host has initialized `ndk-context`. This needs a build that unwinds on panic, Rust's default; with `panic = "abort"` the process aborts. | Released → error from Rust and Java threads; initialized → a client. | `NativeError` | Android |
| LU-JNI-7 | A context whose `getApplicationContext()` is null is used itself. Without the AAR's class on the context's class loader, `LiveUpdate::android()` fails. | A class loader that only knows the system classes → error. | `NativeError` | Android |
| LU-JNI-8 | The whole lifecycle runs from Rust through JNI on a device: capabilities, start, update, list, end. | → revision 1 listed, then `ended`. | — | Android |

## AAR

| ID | Behavior | Input → expected result | Errors | Platform |
| --- | --- | --- | --- | --- |
| LU-AAR-1 | The AAR's manifest declares `POST_NOTIFICATIONS` and `POST_PROMOTED_NOTIFICATIONS`, and both merge into the app's manifest. Asking for the runtime notification permission on API 33 and later stays the app's job. | The test APK requests both. | — | Android |
| LU-AAR-2 | The AAR's consumer ProGuard rules keep the public static methods of `LiveUpdateBridge`, the JNI entry point, under their names. | `proguard.txt` in the AAR has the rule. | — | Android |
| LU-AAR-3 | The AAR holds the bridge's classes and the drawable `liveupdate_status`, under the namespace `io.github.eedaegon.liveupdate`, with minSdk 26. | — | — | Android |

## Android API levels

| ID | Behavior | Input → expected result | Errors | Platform |
| --- | --- | --- | --- | --- |
| LU-API-1 | Below API 36, `supported` and `promotionEnabled` are false, no post requests promotion, and a record that requested it reads `notPromoted`. The lifecycle then falls back as LU-FALLBACK-1 and LU-FALLBACK-2 say. | The adapter at API 35 on an API 37 device → an ongoing notification without the promotion extra. | — | API 26 to 35 |
| LU-API-2 | On API 36 and later, `supported` is true, and promotion follows LU-CAP-2 and LU-CAP-4. | — | — | API 36+ |
| LU-API-3 | API 37 (Android 17) behaves as API 36: the same calls and the same flags. The device suite fails when the emulator runs another API level than the one asked for (`EXPECTED_SDK`). On 2026-11-09 a Pixel 9 Pro XL emulator with the Google APIs image (build CE2A.260420.051.A3) reported promotion available and promoted the notification. | API 37 emulator → `supported`, `promotionEnabled`, `promoted`. | — | API 37 |

## Android presentation v2

| ID | Behavior | Input → expected result | Errors | Platform |
| --- | --- | --- | --- | --- |
| LU-V2-COMPAT-1 | v1 JSON and stored receipts retain their exact shape and behavior. v1 rejects v2 fields; v2 rejects legacy `progress`/`timer` and requires a presentation. | Decode then encode a v1 request → byte-equivalent JSON value. | `InvalidInput` | all |
| LU-V2-MODEL-1 | Schema versions cannot mix presentation generations. A v2 start's template matches its tagged style; an update may carry another one (LU-V2-MODEL-3). | v2 presentation plus legacy progress → error. | `InvalidInput` | all |
| LU-V2-MODEL-2 | v2 supports tagged `standard`, `bigText`, `progress`, `timer`, and `metric` system styles with strict camelCase nested objects. | Each style → stable JSON and matching template. | `InvalidInput` | all |
| LU-V2-PROGRESS-1 | Progress has integer units, at most 32 positive segments and 32 ordered unique points. Progress and points are within the segment total; element IDs are unique per kind. Colors are unsigned ARGB and API 37 semantic styles are closed values. | Zero segment length, point beyond max, duplicate ID/position, 33 elements → error. | `InvalidInput` | all |
| LU-V2-PROGRESS-2 | API 36+ uses `Notification.ProgressStyle`, preserving progress, segments, points, icons, indeterminate/styled flags, IDs and colors. The semantic style of a segment and of a point is a method of API 37: API 37 preserves it, and API 36 posts the style without it. | Two segments and one point → the recovered system style has them. The same on API 36 → posted, with lengths, IDs and colors and no semantic style. | — | API 36+ |
| LU-V2-METRIC-1 | Metric style contains one to three metrics; labels, units and text are bounded, decimal values are finite, and the optional critical index names an existing metric. | Empty, four metrics, NaN, or index past the list → error. | `InvalidInput` | all |
| LU-V2-METRIC-2 | API 37 uses `Notification.MetricStyle` for integer, decimal, text, stopwatch and countdown values; lower APIs retain the title/status standard fallback. A stopwatch counts from its `startedAt` and a countdown to its `endsAt`; the adapter reads no other name for them. | Three typed metrics with critical index 1 → recovered style matches. A stopwatch and a countdown → two time differences with their times; `started_at` → error. | `InvalidInput` | Android |
| LU-V2-METRIC-3 | A stopwatch value is written as `{"kind":"stopwatch","value":{"startedAt":…}}` and a countdown as `{"kind":"countdown","value":{"endsAt":…}}`: the names the adapter reads. The members `started_at` and `ends_at`, which versions up to 0.3.0 wrote and the adapter refused, are still read, in a request and in a stored receipt, and are written in the contract's names. Both names at once, and any other member, are refused. | A start from Rust with both → posted with `startedAt` and `endsAt`. A receipt stored with `started_at` → listed; its next post has `startedAt`. | `InvalidInput` | all |
| LU-V2-ACTION-1 | A snapshot has at most three actions. IDs are 1–64 safe ASCII characters with an alphanumeric first character and are unique; labels and optional icons are bounded. | Duplicate IDs, blank/Unicode ID, fourth action → error. | `InvalidInput` | all |
| LU-V2-ACTION-2 | Each action without a URL is an explicit immutable broadcast `PendingIntent` unique to activity and action, with its requested authentication policy. An action with a URL opens it instead (LU-V2-LINK-3); the content tap is LU-ADAPTER-4's. | Two actions → two unequal immutable intents. | — | Android |
| LU-V2-ICON-1 | Icons are either lowercase app resource names or relative paths under the app's files directory. Absolute paths, `.`/`..`, backslashes, empty segments and overlong paths are rejected. | `../escape.png` → error. | `InvalidInput` | all |
| LU-V2-ICON-2 | The adapter resolves drawable/mipmap resources and bounded app-private image files only. Missing resources, traversal, oversized files or oversized decoded images fail before posting. | An 8×8 app-private PNG → large icon; traversal → no post. | `INVALID_INPUT` | Android |
| LU-V2-VALID-1 | Nested v2 objects reject unknown fields. Big text is at most 4,000 scalar values and short critical text at most 7; all other documented text limits are enforced. | Eight-character chip text or an extra nested member → error. | `InvalidInput` | all |
| LU-V2-VALID-2 | The adapter independently checks the full v2 shape and posts nothing for an unknown style, bad progress/metric/action/icon, schema mixing, or over-limit text. | Any malformed direct bridge record → no notification. | `INVALID_INPUT` | Android |
| LU-V2-ADAPTER-1 | Rich styles use only Android system templates, never custom `RemoteViews`. Promotion characteristics remain valid when the request and device allow promotion. | Rich progress → `hasPromotableCharacteristics()`. | — | Android |
| LU-V2-ADAPTER-2 | Standard and BigText use their system styles; timer counts down to `endsAt` or up from `startedAt`. Below a rich style's API, progress becomes a classic bar and metrics become standard title/status text. | Adapter simulated at API 35 → safe fallback. | — | Android |
| LU-V2-CAP-1 | Capabilities report Standard, BigText, Timer, ProgressStyle, MetricStyle, actions, images, events and Samsung Now Bar separately. ProgressStyle needs API 36, MetricStyle API 37, and Samsung needs a Samsung API 37+ device. | API/manufacturer → exact feature booleans. | — | Android |
| LU-V2-EVENT-1 | Events have a safe sequence, activity id, timestamp and a strict tagged action, dismissed or opened (LU-V2-EVENT-7) kind; batches include a dropped count. | Unknown event member → decode error. | `InvalidInput` or serde error | all |
| LU-V2-EVENT-2 | `LiveUpdate::events()` atomically drains through the same client lock; the next drain is empty. | One queued action → one batch, then empty. | driver's | all |
| LU-V2-EVENT-3 | `Driver::drain_events` defaults to an empty batch so existing custom drivers remain source-compatible. | Driver without an override → empty. | — | all |
| LU-V2-EVENT-4 | Non-exported action and dismiss receivers append durable events; the `events` bridge command and Rust JNI return them in sequence and clear them. | Tap then dismiss → action then dismissed. | — | Android |
| LU-V2-EVENT-5 | The durable queue keeps at most 128 newest events. Overflow drops oldest events and increments a count returned by the next drain. | Append 130 → events 2…129 and dropped 2. | — | Android |
| LU-V2-EVENT-6 | An app may set one listener with `LiveUpdateEvents.setListener`, which the action and dismiss receivers call once each event is stored, on the receiver's thread, so the app can drain at once; a new listener replaces the old one, `null` clears it, and a listener that throws is logged and never loses the event. | Tap then dismiss → the listener runs twice, with one and then two events queued. | — | Android |
| LU-V2-SAMSUNG-1 | Samsung Now Bar mapping is a closed helper enabled only on API 37+, producing only the five known ongoing-activity text/style extras. | API 36 or absent option → no extras. | — | Android |
| LU-V2-SAMSUNG-2 | A v2 presentation opts into Samsung extras explicitly; they are absent by default and cannot be supplied as an arbitrary map. | Primary/secondary → known One UI extras. | — | Android |
| LU-V2-JNI-1 | A complete v2 presentation and its action event cross Rust → JSON/JNI → AAR → Rust without changing semantic data. | Non-ASCII action and rich progress → same receipt and action ID. | — | Android |

## Android presentation extensions

Optional parts of schema version 2 (`docs/design/android-core-extensions`). Each is left out of the JSON
when unset, and schema version 1 has none of them.

| ID | Behavior | Input → expected result | Errors | Platform |
| --- | --- | --- | --- | --- |
| LU-V2-COMPAT-2 | A presentation and an action without the members of this section serialize exactly as before them. `AndroidPresentation::new(style)` and `NotificationAction::new(id, title)` set none, so a caller names only what it uses. | `AndroidPresentation::new(Standard)` ↔ `{"style":{"kind":"standard"}}`. | — | all |
| LU-V2-CAP-2 | Capabilities report each extension separately: `chronometer`, `links`, `styleChange`, `delayedEnd`, `foregroundPlacement`, `alert`, `publicVersion` and `branding`. A reply that leaves a presentation member out reads it as false. The AAR reports all eight. | A reply with `{"chronometer": true}` alone → only that one. | — | all; Android |
| LU-V2-CHRONO-1 | A presentation may carry a `chronometer`: a timer and a direction, with the timer style's bounds, beside any style but `timer`, which is that clock alone. It does not change the activity's template. | A progress style with a chronometer → valid, a progress activity; with the timer style → error; an end at or before its start → error. | `InvalidInput` | all |
| LU-V2-CHRONO-2 | The adapter shows the chronometer in the notification's header beside the style, counting down to `endsAt` or up from `startedAt`, and keeps the style and its promotable characteristics. It checks the member again and posts nothing for one the contract refuses. | A progress style with a countdown → `ProgressStyle`, a countdown to `endsAt`. | `INVALID_INPUT` | Android |
| LU-V2-LINK-1 | A link (`openUrl` of a presentation or of an action) is 1 to 2048 printable ASCII characters: a lowercase scheme (`[a-z][a-z0-9+.-]*`), a colon and something after it. The schemes `intent`, `content`, `file`, `javascript`, `data` and `android-app` are refused. Rust and the adapter apply the same rule. | `myapp://orders/42` → valid; `MyApp://x`, `myapp:`, a space, `intent://x`, 2049 characters → error. | `InvalidInput` | all |
| LU-V2-LINK-2 | With `openUrl`, the content tap opens the URL with `ACTION_VIEW` inside the application's own package, through an immutable activity `PendingIntent` whose intent carries the activity's id. | A tap → the package's activity for the URL, with the id. | — | Android |
| LU-V2-LINK-3 | An action with `openUrl` opens it the same way, with the activity's and the action's id, and queues no `action` event: Android lets no receiver of a notification start an activity. The same URL in two activities gives two intents, also when one activity's id reads like another's id and action. | A tap → the activity for the URL; the event queue stays empty. | — | Android |
| LU-V2-LINK-4 | A URL that the rule refuses, or that no activity of the package opens, fails the post, as the content's and as an action's, and nothing is posted. | `nobody-opens-this://x` → error, no notification. | `INVALID_INPUT` | Android |
| LU-V2-LINK-5 | `LiveUpdateEvents.opened(context, intent)` queues one `opened` event for an intent that carries the library's marks and names a stored activity, tells the events listener, removes the marks and answers true. It answers false, and queues nothing, for the same intent again, an intent without the marks, an activity that is not stored and a malformed id. | The content tap's intent → `{"kind":"opened"}`; an opening action's → with its `actionId`. | — | Android |
| LU-V2-EVENT-7 | An `opened` event names its action only when one opened the application: `{"kind":"opened"}` or `{"kind":"opened","actionId":"…"}`, with the action id's bounds and no other member. | An action id with a space, or a `url` member → error. | `InvalidInput` or serde error | all |
| LU-V2-MODEL-3 | A schema-2 update may carry another system style. The record's `template` is the style of its latest snapshot, the request of the start stays its origin, and the same start still answers the running activity. A notification that still shows the previous style while the newer revision is awaited is not a contradiction. The adapter builds the snapshot's style whatever the start's was, and refuses an origin whose template is no style. | Progress, then BigText → `template: bigText`. | `InvalidInput`; `INVALID_INPUT` | all; Android |
| LU-END-7 | With an adapter that reports `delayedEnd`, `end(id, EndPolicy::After { at })` stores the record as `ended` with `dismissAt` and posts it instead of cancelling. A live activity's receipt has no `dismissAt`. A second delayed end does nothing, also when the adapter no longer offers it; an end without a time then removes the last content and clears `dismissAt`. | → driver calls read, observe, capabilities, write, post; `ended`, `dismissAt: at`. | — | all |
| LU-END-8 | A delayed end does not post what the user removed: a dismissed activity is ended without a `dismissAt`. When the post of the last content fails, the record stays `ended` and the next reconciliation cancels the live notification Android still shows. | The post fails → error; the next `list` → cancelled. | `NativeError` | all |
| LU-END-9 | The adapter posts an ended record with `dismissAt` as the activity's last content: no longer ongoing, without the promotion request, with a timeout at `dismissAt`, replacing the notification in place. Android removes it at that time. It is no longer observed, and it has no delete intent, so it queues no event when it goes. A `dismissAt` that has passed removes the notification instead, and one on a record that is not ended is refused. | `dismissAt` in a minute → not ongoing, no observation; in the past → gone. | `INVALID_INPUT` | Android |
| LU-FGS-1 | A start may ask for the foreground `placement`, so that a foreground service of the application can hold the notification. It is part of the request, serialized as `"placement":"foreground"` and left out when tagged. Schema version 1 has none. | A schema-1 start with it → error; an unknown placement → decode error. | `InvalidInput` | all |
| LU-FGS-2 | The foreground placement needs an adapter that reports `foregroundPlacement`, and one live activity has it at a time. The same start answers the running activity, another placement under its key is a conflict, and a tagged activity is not limited by it. The placement is free once its holder ended. | No capability → error, nothing posted; a second key → error. | `UnsupportedFeature`, `SystemLimit`, `Conflict` | all |
| LU-FGS-3 | The notification of a foreground placement has no tag and the id `LiveUpdateForeground.NOTIFICATION_ID`, as `startForeground` needs. Observation lists it by the record it carries and ignores another notification of the application under that id; cancel removes it; tagged activities are untouched. A schema-1 origin with the placement and an unknown placement are refused. | — | `INVALID_INPUT` | Android |
| LU-FGS-4 | `LiveUpdateForeground.attach(service, type)` puts the service in the foreground with the notification of the stored live activity that has the placement: the posted notification becomes the service's without being removed, and keeps its promotable characteristics. A later post is shown through the service, so that it stays the service's and is what Android posts again when the service detaches. An attach never alerts, whatever the stored revision says. | Post, attach, post → one notification, the same key, held by the service. | — | Android |
| LU-FGS-5 | Cancelling a held activity takes the service out of the foreground, removes the notification and tells the listener of `LiveUpdateForeground` its id, whether or not Android lists the notification yet. A listener that throws changes nothing. | Cancel → gone, `onReleased("…")`. | — | Android |
| LU-FGS-6 | `LiveUpdateForeground.detach(service)` leaves the notification with its latest content, no longer the service's, and the library no longer holds the service; the service may attach again. `attach` answers false and changes nothing when no stored activity has the placement, or the one that has it is ended or dismissed. It also answers false for stored receipts it cannot read. A stored live one that Android no longer shows, as after a killed process, is shown again. | — | — | Android |
| LU-FGS-7 | The last content of an activity with the foreground placement (LU-END-9) has the activity's own tag. A service that holds the activity leaves the foreground and its notification goes with it, and the listener is told. The placement is then free for the next activity, beside that last content. | A delayed end of a held activity → the service's notification gone, the last content under `liveupdate-rs:<id>`. | — | Android |
| LU-FGS-8 | What the placement rests on in Android, with a foreground service and a notification listener of the test package: a notification posted without a tag becomes the service's when `startForeground` names its id, without being removed; a post under that id keeps it; a cancel does nothing while the service holds it; `STOP_FOREGROUND_DETACH` leaves the notification, no longer the service's, with what the service was last given, and `STOP_FOREGROUND_REMOVE` removes it; a killed process takes its foreground notification with it. | — | — | Android |
| LU-V2-ALERT-1 | `alert` marks one snapshot as a revision that may alert again. It is a flag of the presentation, left out when false, and is not carried over to the next snapshot. | `"alert": 1` → decode error. | — | all |
| LU-V2-ALERT-2 | A revision that alerts is posted on the channel `liveupdate-rs.alerts.v1`, named "Live update alerts", at `IMPORTANCE_HIGH`, without alerting only once; the next revision returns to the channel of LU-CAP-3. Both replace the notification in place. A user who blocked the alerting channel gets the revision on the quiet one. The last content of an ended activity never alerts. | — | `INVALID_INPUT` (not a boolean) | Android |
| LU-V2-PUBLIC-1 | A `publicVersion` is a title and a status with the snapshot's bounds, for the secure lock screen. Only a private activity may have one, at start and in an update. | A public or secret start with one → error; an update of a public activity with one → error. | `InvalidInput` | all |
| LU-V2-PUBLIC-2 | The adapter sets the public version as the notification's, with the presentation's small icon, and refuses one on an activity that is not private or with another member. | — | `INVALID_INPUT` | Android |
| LU-V2-BRAND-1 | Branding is a `smallIcon` that is a resource, an ARGB `accentColor` and a `subText` of at most 64 characters. | An app-file small icon, 65 characters, a color above 32 bits → error. | `InvalidInput` or serde error | all |
| LU-V2-BRAND-2 | The adapter sets the small icon, also on the public version, the color and the sub text; without them the notification keeps `liveupdate_status` and has no sub text. A small icon that is no resource of the application is refused. | — | `INVALID_INPUT` | Android |

## Docs

| ID | Behavior | Input → expected result | Errors | Platform |
| --- | --- | --- | --- | --- |
| LU-DOCS-1 | The README's Rust code is the schema-v2 rich progress example in `examples/delivery.rs`, verbatim: it demonstrates a typed `AndroidPresentation`, progress segments and an action; CI compiles it for Android (`cargo ndk check --all-targets --features android`) and for the host, and it compiles as a doctest. | A typo in `LiveUpdate::android()`, or replacing the example with schema v1 → the documentation checks fail. | — | all |
| LU-DOCS-2 | The README installs this version of the crate, the AAR of its release and the Kotlin standard library the AAR was compiled against, and says what an app must know: runtime permission, `ndk-context`, one client, how an end leaves the screen, unknown observed visibility, v2/event scope and the tested S23 firmware, and how each feature is used, feature by feature. | — | — | all |

## Not proven by a test

- **One client per process.** Two clients over the same storage are not serialized against each other;
  their read-modify-write cycles can interleave. The README says to keep one.
- **OEM promotion policy and appearance.** The S23 verifies API 37 notification structure and One UI
  extras, but no automated assertion can guarantee that a user's current promotion setting or an
  OEM allow-list gives every app the same visual prominence. Other OEM system UIs remain untested.
- **Process death between post and confirmation.** The retry of LU-START-5 is tested with a failing
  driver, not with a killed process.
- **What the system UI makes of an extension.** The tests read the built notification back: its
  intents, clock, channel, public version and key. Whether a lock screen shows the public version of a
  promoted notification, and whether a status chip shows the clock, is the system UI's.
- **Who sends an opening intent.** `LiveUpdateEvents.opened` counts an intent with the library's marks
  for a stored activity. An activity that other applications may start should pass it only intents it
  trusts. The lifecycle's ids are random and other applications do not see them, short of notification
  access.
- **Distinct intents below API 29.** From API 29 an opening intent is told apart by its identifier. Below,
  only the request code tells two activities with the same URL apart, and it is a hash of their ids.
- **Receipts and events of 0.2.0 and 0.2.1.** Those two versions named the Android namespace
  `io.github.gon.liveupdate`, and the preferences file after it. The namespace is again
  `io.github.eedaegon.liveupdate` and the preferences file `io.github.1eedaegon.liveupdate`, as in 0.1:
  what 0.1 stored is read, and what 0.2.0 and 0.2.1 stored is not.
- **An end right after a detach.** When a service detaches, Android posts the notification that the
  service was last given once more, a moment later: on the API 36 and 37 emulators a revision posted past
  the service came back as the older one, and a notification cancelled right after
  `STOP_FOREGROUND_DETACH` sometimes stayed. The adapter therefore updates a held activity through its
  service (LU-FGS-4) and ends it through the service (LU-FGS-5, LU-FGS-7). An activity that ends in the
  instant after `LiveUpdateForeground.detach` can still keep its notification until the next lifecycle
  call, whose reconciliation cancels it (LU-LIST-7).
- **A host's foreground service.** The type, the permission and the moment a service may start are the
  application's. The device tests use a service of the test package; no test starts one from the
  background as a user's application would.
- **Android's rate limit for updates.** Android's notification service drops updates of a notification
  that an app posts faster than its enqueue rate allows. A dropped update would leave its record
  `unknown` until a later update or end, and a record that stays `unknown` is not reported as dismissed
  when the user removes the notification (LU-LIST-4). On the API 37 emulator, 100 updates in a row,
  each with its storage writes, did not reach the rate: the record was `active` three seconds later.

## Tests

| Requirement | Tests |
| --- | --- |
| LU-CLIENT-1 | `tests/lifecycle.rs::concurrent_operations_of_one_client_run_one_at_a_time` (integration)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/NativeRustInstrumentationTest.kt::manyRustThreadsShareOneClient` (device) |
| LU-CLIENT-2 | `tests/lifecycle.rs::a_driver_panic_does_not_disable_the_client` (integration) |
| LU-START-1 | `src/driver.rs::the_public_record_keeps_the_origin_and_hides_the_visibility` (unit)<br>`tests/lifecycle.rs::a_start_writes_a_pending_receipt_before_it_posts` (integration)<br>`tests/reconcile.rs::a_post_android_lists_late_is_confirmed_and_not_posted_again` (integration) |
| LU-START-2 | `tests/lifecycle.rs::duplicate_start_and_end_are_idempotent_without_reusing_handles` (integration) |
| LU-START-3 | `tests/lifecycle.rs::a_different_request_under_a_live_key_conflicts` (integration) |
| LU-START-4 | `tests/lifecycle.rs::duplicate_start_and_end_are_idempotent_without_reusing_handles` (integration) |
| LU-START-5 | `tests/lifecycle.rs::posting_failures_leave_retryable_receipts` (integration)<br>`tests/reconcile.rs::a_post_android_lists_late_is_confirmed_and_not_posted_again` (integration) |
| LU-START-6 | `src/client/tests.rs::lookups_and_permission_have_their_own_codes` (unit)<br>`tests/lifecycle.rs::start_checks_input_then_permission_then_the_key_then_fallback_then_the_limit` (integration) |
| LU-START-7 | `tests/lifecycle.rs::receipts_and_keys_survive_a_new_client` (integration)<br>`tests/reconcile.rs::durable_records_reconcile_after_restart_and_observed_dismissal` (integration)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/NativeRustInstrumentationTest.kt::textAndTimersCrossJniUnchanged` (device) |
| LU-UPDATE-1 | `tests/lifecycle.rs::a_newer_revision_replaces_the_content` (integration) |
| LU-UPDATE-2 | `src/client/tests.rs::a_snapshot_replaces_the_accepted_one_only_with_a_higher_revision` (unit)<br>`tests/lifecycle.rs::revisions_reject_stale_conflicting_and_template_changing_updates` (integration) |
| LU-UPDATE-3 | `src/client/tests.rs::a_snapshot_replaces_the_accepted_one_only_with_a_higher_revision` (unit)<br>`tests/lifecycle.rs::revisions_reject_stale_conflicting_and_template_changing_updates` (integration)<br>`tests/lifecycle.rs::the_same_revision_retries_a_failed_post` (integration) |
| LU-UPDATE-4 | `src/client/tests.rs::a_snapshot_replaces_the_accepted_one_only_with_a_higher_revision` (unit)<br>`tests/lifecycle.rs::revisions_reject_stale_conflicting_and_template_changing_updates` (integration) |
| LU-UPDATE-5 | `src/client/tests.rs::a_snapshot_replaces_the_accepted_one_only_with_a_higher_revision` (unit)<br>`tests/lifecycle.rs::revisions_reject_stale_conflicting_and_template_changing_updates` (integration)<br>`tests/extensions.rs::a_schema_two_update_may_change_the_style_and_the_record_follows_it` (integration) |
| LU-UPDATE-6 | `src/client/tests.rs::lookups_and_permission_have_their_own_codes` (unit)<br>`tests/lifecycle.rs::updates_of_unknown_ended_or_dismissed_activities_are_not_found` (integration)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/NativeRustInstrumentationTest.kt::aNotificationTheUserRemovesBecomesDismissed` (device) |
| LU-UPDATE-7 | `tests/lifecycle.rs::updates_need_notifications_and_keep_the_promotion_of_the_start` (integration) |
| LU-UPDATE-8 | `tests/lifecycle.rs::a_newer_revision_replaces_the_content` (integration) |
| LU-END-1 | `src/driver.rs::ended_and_dismissed_records_are_terminal` (unit)<br>`tests/lifecycle.rs::an_ended_record_no_longer_awaits_a_post` (integration)<br>`tests/lifecycle.rs::end_stores_the_ended_record_then_removes_the_notification` (integration) |
| LU-END-2 | `tests/lifecycle.rs::duplicate_start_and_end_are_idempotent_without_reusing_handles` (integration) |
| LU-END-3 | `tests/lifecycle.rs::a_delayed_end_needs_an_adapter_that_offers_it` (integration) |
| LU-END-4 | `tests/lifecycle.rs::ending_a_dismissed_activity_marks_it_ended` (integration) |
| LU-END-5 | `tests/lifecycle.rs::a_failed_cancel_is_repeated_by_the_next_operation` (integration) |
| LU-END-6 | `tests/lifecycle.rs::end_needs_a_known_valid_id` (integration) |
| LU-LIST-1 | `tests/reconcile.rs::list_returns_active_and_terminal_records` (integration) |
| LU-LIST-2 | `src/client/tests.rs::one_record_moves_between_states_by_what_android_shows` (unit)<br>`tests/reconcile.rs::an_observation_sets_the_state_and_the_promotion_of_a_record` (integration) |
| LU-LIST-3 | `src/client/tests.rs::one_record_moves_between_states_by_what_android_shows` (unit)<br>`src/driver.rs::ended_and_dismissed_records_are_terminal` (unit)<br>`tests/reconcile.rs::durable_records_reconcile_after_restart_and_observed_dismissal` (integration)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/NativeRustInstrumentationTest.kt::aNotificationTheUserRemovesBecomesDismissed` (device) |
| LU-LIST-4 | `src/client/tests.rs::one_record_moves_between_states_by_what_android_shows` (unit)<br>`tests/reconcile.rs::a_record_android_has_not_shown_yet_stays_unknown` (integration) |
| LU-LIST-5 | `src/client/tests.rs::one_record_moves_between_states_by_what_android_shows` (unit)<br>`tests/reconcile.rs::delayed_observation_preserves_accepted_revisions` (integration) |
| LU-LIST-6 | `src/client/tests.rs::an_observation_must_match_the_promotion_and_the_template_of_its_receipt` (unit)<br>`src/client/tests.rs::observations_are_bounded_unique_and_active_or_stale` (unit)<br>`tests/reconcile.rs::a_failed_observation_fails_the_operation` (integration)<br>`tests/reconcile.rs::observations_that_contradict_the_receipt_fail_closed` (integration)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/AndroidAdapterInstrumentationTest.kt::anUnreadableNotificationOfTheAdapterFailsTheObservation` (device) |
| LU-LIST-7 | `src/client/tests.rs::settle_cancels_ended_notifications_and_ignores_unknown_ones` (unit)<br>`tests/reconcile.rs::visible_notifications_of_ended_activities_are_cancelled` (integration) |
| LU-LIST-8 | `src/client/tests.rs::settle_cancels_ended_notifications_and_ignores_unknown_ones` (unit)<br>`tests/reconcile.rs::notifications_without_a_receipt_are_ignored` (integration) |
| LU-LIST-9 | `tests/reconcile.rs::list_writes_only_when_reconciliation_changes_a_record` (integration) |
| LU-LIMIT-1 | `tests/reconcile.rs::active_and_terminal_records_are_bounded` (integration) |
| LU-LIMIT-2 | `src/client/tests.rs::a_record_that_turns_terminal_becomes_the_newest_tombstone` (unit)<br>`src/client/tests.rs::save_keeps_active_records_first_and_the_newest_128_tombstones` (unit)<br>`tests/reconcile.rs::the_oldest_tombstone_is_evicted_first` (integration) |
| LU-LIMIT-3 | `src/client/tests.rs::stored_records_are_checked_before_use` (unit)<br>`tests/reconcile.rs::corrupt_driver_records_fail_closed` (integration)<br>`android/src/test/java/io/github/eedaegon/liveupdate/LiveUpdateProtocolTest.kt::boundsPayloadBytesAndRecordCounts` (kotlin)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/AndroidAdapterInstrumentationTest.kt::storageWritesAreBoundedInCountAndBytes` (device) |
| LU-LIMIT-4 | `src/client/tests.rs::observations_are_bounded_unique_and_active_or_stale` (unit)<br>`tests/reconcile.rs::ended_notifications_android_still_lists_do_not_block_the_active_limit` (integration)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/AndroidAdapterInstrumentationTest.kt::observationReadsAtMost160OfTheAdaptersNotifications` (device) |
| LU-CAP-1 | `src/contract.rs::posting_needs_notifications_and_promotion_needs_all_three` (unit)<br>`tests/contract.rs::capabilities_use_the_stable_camel_case_contract` (integration)<br>`tests/contract.rs::capability_helpers_separate_regular_posting_from_promotion` (integration)<br>`tests/reconcile.rs::capabilities_come_from_the_driver_unchanged` (integration) |
| LU-CAP-2 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/LiveUpdateBridgeInstrumentationTest.kt::capabilitiesReflectTheRunningDevice` (device)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/PermissionInstrumentationTest.kt::withoutTheNotificationPermissionPostsAreDenied` (device) |
| LU-CAP-3 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/AndroidAdapterInstrumentationTest.kt::capabilitiesCreateTheLowImportanceChannel` (device) |
| LU-CAP-4 | `src/client/tests.rs::an_observation_must_match_the_promotion_and_the_template_of_its_receipt` (unit)<br>`src/driver.rs::a_record_is_degraded_unless_promotion_was_requested_and_not_refused` (unit)<br>`tests/reconcile.rs::an_observation_sets_the_state_and_the_promotion_of_a_record` (integration)<br>`tests/reconcile.rs::each_post_makes_the_promotion_state_unknown_until_android_lists_it` (integration)<br>`tests/reconcile.rs::notification_permission_and_explicit_fallback_are_enforced` (integration)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/NativeRustInstrumentationTest.kt::promotionIsRequestedWhereverAndroidAllowsIt` (device) |
| LU-CAP-5 | `src/contract.rs::promotion_states_read_unknown_names_as_unknown` (unit)<br>`tests/contract.rs::an_unrecognized_promotion_state_is_forward_compatible` (integration) |
| LU-FALLBACK-1 | `tests/contract.rs::capability_helpers_separate_regular_posting_from_promotion` (integration)<br>`tests/reconcile.rs::without_promotion_the_fallback_posts_a_regular_notification` (integration) |
| LU-FALLBACK-2 | `tests/reconcile.rs::notification_permission_and_explicit_fallback_are_enforced` (integration) |
| LU-FALLBACK-3 | `tests/reconcile.rs::promotion_is_requested_whenever_it_is_available` (integration)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/NativeRustInstrumentationTest.kt::promotionIsRequestedWhereverAndroidAllowsIt` (device) |
| LU-VALID-1 | `src/model.rs::activity_keys_are_short_ascii_names` (unit) |
| LU-VALID-2 | `src/model.rs::a_start_needs_schema_one_and_a_known_visibility` (unit) |
| LU-VALID-3 | `src/model.rs::a_start_needs_schema_one_and_a_known_visibility` (unit) |
| LU-VALID-4 | `src/model.rs::text_made_only_of_contract_whitespace_is_empty` (unit)<br>`src/model.rs::titles_and_statuses_count_unicode_scalar_values` (unit) |
| LU-VALID-5 | `src/model.rs::a_snapshot_has_exactly_one_template_value` (unit) |
| LU-VALID-6 | `src/model.rs::progress_is_finite_from_zero_to_one` (unit) |
| LU-VALID-7 | `src/model.rs::a_timer_ends_after_it_starts_within_the_timestamp_range` (unit) |
| LU-VALID-8 | `src/model.rs::revisions_and_timestamps_have_javascript_safe_maximums` (unit) |
| LU-VALID-9 | `src/model.rs::end_policies_and_states_use_the_stable_json_names` (unit)<br>`src/model.rs::request_json_rejects_unknown_fields` (unit) |
| LU-VALID-10 | `src/model.rs::activity_ids_are_non_blank_and_at_most_256_characters` (unit)<br>`android/src/test/java/io/github/eedaegon/liveupdate/LiveUpdateProtocolTest.kt::activityIdentifierLimitsCountUnicodeCodePoints` (kotlin)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/RichPresentationInstrumentationTest.kt::eventActivityIdentifierLimitsCountUnicodeCodePoints` (device) |
| LU-VALID-11 | `tests/lifecycle.rs::invalid_requests_never_reach_the_driver` (integration) |
| LU-VALID-12 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/AndroidAdapterInstrumentationTest.kt::postsThatBreakTheContractAreRejectedWithoutANotification` (device) |
| LU-ERR-1 | `src/error.rs::errors_carry_a_code_and_a_message_and_nothing_else` (unit)<br>`src/error.rs::every_code_has_one_stable_name` (unit) |
| LU-ERR-2 | `src/error.rs::the_library_messages_are_fixed_sentences` (unit)<br>`tests/lifecycle.rs::error_messages_never_repeat_notification_content` (integration) |
| LU-ERR-3 | `tests/bridge.rs::adapter_errors_are_preserved_without_accepting_malformed_envelopes` (integration)<br>`android/src/test/java/io/github/eedaegon/liveupdate/LiveUpdateProtocolTest.kt::preservesStableAdapterErrors` (kotlin)<br>`android/src/test/java/io/github/eedaegon/liveupdate/LiveUpdateProtocolTest.kt::sanitizesUnexpectedAdapterAndParserFailures` (kotlin)<br>`android/src/test/java/io/github/eedaegon/liveupdate/LiveUpdateProtocolTest.kt::aPlatformMethodTheDeviceLacksIsANativeError` (kotlin) |
| LU-ERR-4 | `android/src/test/java/io/github/eedaegon/liveupdate/LiveUpdateProtocolTest.kt::adapterFailuresOnlyTakeTheStableCodes` (kotlin) |
| LU-BRIDGE-1 | `src/bridge.rs::requests_serialize_as_the_adapter_expects` (unit)<br>`tests/bridge.rs::a_client_over_the_json_bridge_runs_the_whole_lifecycle` (integration)<br>`tests/bridge.rs::every_driver_operation_uses_a_bounded_stable_command` (integration)<br>`tests/contract.rs::platform_policy_enums_use_the_stable_contract` (integration)<br>`tests/contract.rs::records_cross_the_bridge_with_the_names_the_adapter_reads` (integration)<br>`tests/contract.rs::public_records_do_not_leak_cross_platform_backend_discriminators` (integration) |
| LU-BRIDGE-2 | `src/bridge.rs::a_request_over_one_mebibyte_never_reaches_the_bridge` (unit) |
| LU-BRIDGE-3 | `src/bridge.rs::a_reply_of_exactly_one_mebibyte_is_read_and_one_byte_more_is_not` (unit)<br>`tests/bridge.rs::oversized_adapter_replies_fail_closed` (integration) |
| LU-BRIDGE-4 | `tests/bridge.rs::adapter_errors_are_preserved_without_accepting_malformed_envelopes` (integration)<br>`tests/bridge.rs::reply_data_may_gain_members_but_unit_replies_must_be_null` (integration) |
| LU-BRIDGE-5 | `tests/bridge.rs::a_bridge_error_is_returned_as_it_is` (integration) |
| LU-BRIDGE-6 | `android/src/test/java/io/github/eedaegon/liveupdate/LiveUpdateProtocolTest.kt::activityIdentifierLimitsCountUnicodeCodePoints` (kotlin)<br>`android/src/test/java/io/github/eedaegon/liveupdate/LiveUpdateProtocolTest.kt::answersWithTheStableEnvelope` (kotlin)<br>`android/src/test/java/io/github/eedaegon/liveupdate/LiveUpdateProtocolTest.kt::boundsPayloadBytesAndRecordCounts` (kotlin)<br>`android/src/test/java/io/github/eedaegon/liveupdate/LiveUpdateProtocolTest.kt::dispatchesEveryStableDriverCommand` (kotlin)<br>`android/src/test/java/io/github/eedaegon/liveupdate/LiveUpdateProtocolTest.kt::optionalStringsAreStringsOnly` (kotlin)<br>`android/src/test/java/io/github/eedaegon/liveupdate/LiveUpdateProtocolTest.kt::rejectsUnknownCommandsFieldsAndInvalidIdentifiers` (kotlin)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/LiveUpdateBridgeInstrumentationTest.kt::theBridgeRejectsMalformedCommandsBeforeTheFramework` (device)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/LiveUpdateBridgeInstrumentationTest.kt::theBridgeUsesTheApplicationContext` (device) |
| LU-BRIDGE-7 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/AndroidAdapterInstrumentationTest.kt::storageWritesAreBoundedInCountAndBytes` (device)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/LiveUpdateBridgeInstrumentationTest.kt::storageStartsEmptyAndRejectsCorruptContent` (device) |
| LU-ADAPTER-1 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/AndroidAdapterInstrumentationTest.kt::aProgressRecordPostsAnOngoingSystemTemplate` (device)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/AndroidAdapterInstrumentationTest.kt::visibilityFollowsTheOrigin` (device)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/LiveUpdateBridgeInstrumentationTest.kt::durableRecordPostsObservesAndCancelsThroughThePublicBridge` (device) |
| LU-ADAPTER-2 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/AndroidAdapterInstrumentationTest.kt::aProgressRecordPostsAnOngoingSystemTemplate` (device) |
| LU-ADAPTER-3 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/AndroidAdapterInstrumentationTest.kt::aTimerRecordPostsACountdownToItsEnd` (device)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/NativeRustInstrumentationTest.kt::textAndTimersCrossJniUnchanged` (device) |
| LU-ADAPTER-4 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/AndroidAdapterInstrumentationTest.kt::aProgressRecordPostsAnOngoingSystemTemplate` (device)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/AndroidAdapterInstrumentationTest.kt::anAppWithoutALaunchActivityGetsNoContentIntent` (device)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/ExtensionsInstrumentationTest.kt::theContentTapOpensItsUrlInThePackageAndNamesTheActivity` (device) |
| LU-ADAPTER-5 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/AndroidAdapterInstrumentationTest.kt::belowApi36NothingIsPromoted` (device)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/AndroidAdapterInstrumentationTest.kt::promotionIsRequestedOnlyWhenTheRecordAsksForItOnApi36AndLater` (device) |
| LU-ADAPTER-6 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/AndroidAdapterInstrumentationTest.kt::aStaleTimeInThePastIsObservedAsStale` (device)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/AndroidAdapterInstrumentationTest.kt::anUnreadableNotificationOfTheAdapterFailsTheObservation` (device)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/AndroidAdapterInstrumentationTest.kt::observationReadsAtMost160OfTheAdaptersNotifications` (device)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/AndroidAdapterInstrumentationTest.kt::observationReportsOnlyTheAdaptersOwnNotifications` (device)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/LiveUpdateBridgeInstrumentationTest.kt::durableRecordPostsObservesAndCancelsThroughThePublicBridge` (device) |
| LU-ADAPTER-7 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/PermissionInstrumentationTest.kt::withoutTheNotificationPermissionPostsAreDenied` (device) |
| LU-ADAPTER-8 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/AndroidAdapterInstrumentationTest.kt::cancelRemovesOnlyItsOwnNotification` (device)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/LiveUpdateBridgeInstrumentationTest.kt::durableRecordPostsObservesAndCancelsThroughThePublicBridge` (device) |
| LU-ADAPTER-9 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/LiveUpdateBridgeInstrumentationTest.kt::durableRecordPostsObservesAndCancelsThroughThePublicBridge` (device)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/LiveUpdateBridgeInstrumentationTest.kt::storageStartsEmptyAndRejectsCorruptContent` (device) |
| LU-JNI-1 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/NativeRustInstrumentationTest.kt::nativeRustRunsTheCompleteLifecycleOnARustThread` (device) |
| LU-JNI-2 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/NativeRustInstrumentationTest.kt::manyRustThreadsShareOneClient` (device)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/NativeRustInstrumentationTest.kt::nativeRustRunsTheCompleteLifecycleOnARustThread` (device)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/NativeRustInstrumentationTest.kt::nativeRustRunsTheLifecycleOnTheCallingJavaThread` (device) |
| LU-JNI-3 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/NativeRustInstrumentationTest.kt::manyCallsInARowKeepWorking` (device) |
| LU-JNI-4 | `tests/bridge.rs::a_client_over_the_json_bridge_runs_the_whole_lifecycle` (integration)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/NativeRustInstrumentationTest.kt::textAndTimersCrossJniUnchanged` (device) |
| LU-JNI-5 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/NativeRustInstrumentationTest.kt::aContextThatThrowsMakesTheClientAnError` (device)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/NativeRustInstrumentationTest.kt::aContextWithoutAnApplicationIsUsedAndItsJavaExceptionsAreErrors` (device) |
| LU-JNI-6 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/NativeRustInstrumentationTest.kt::withoutNdkContextTheClientIsAnErrorAndLaterSucceeds` (device) |
| LU-JNI-7 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/NativeRustInstrumentationTest.kt::aContextWithoutAnApplicationIsUsedAndItsJavaExceptionsAreErrors` (device)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/NativeRustInstrumentationTest.kt::withoutTheAarClassesTheClientIsAnError` (device) |
| LU-JNI-8 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/NativeRustInstrumentationTest.kt::nativeRustRunsTheCompleteLifecycleOnARustThread` (device) |
| LU-AAR-1 | `tests/android/aar.sh::manifest_declares_both_notification_permissions` (build)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/LiveUpdateBridgeInstrumentationTest.kt::theManifestMergesBothNotificationPermissions` (device) |
| LU-AAR-2 | `tests/android/aar.sh::consumer_rules_keep_the_jni_entry_point` (build) |
| LU-AAR-3 | `tests/android/aar.sh::the_aar_holds_the_bridge_and_its_resources` (build) |
| LU-API-1 | `tests/reconcile.rs::without_promotion_the_fallback_posts_a_regular_notification` (integration)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/AndroidAdapterInstrumentationTest.kt::belowApi36NothingIsPromoted` (device) |
| LU-API-2 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/AndroidAdapterInstrumentationTest.kt::promotionIsRequestedOnlyWhenTheRecordAsksForItOnApi36AndLater` (device)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/LiveUpdateBridgeInstrumentationTest.kt::capabilitiesReflectTheRunningDevice` (device)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/NativeRustInstrumentationTest.kt::promotionIsRequestedWhereverAndroidAllowsIt` (device) |
| LU-API-3 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/LiveUpdateBridgeInstrumentationTest.kt::capabilitiesReflectTheRunningDevice` (device)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/NativeRustInstrumentationTest.kt::promotionIsRequestedWhereverAndroidAllowsIt` (device) |
| LU-DOCS-1 | `tests/docs.rs::the_readme_shows_the_compiled_example_whole` (integration)<br>`tests/docs.rs::the_primary_example_uses_the_rich_schema` (integration)<br>`src/lib.rs::ReadmeDoctests` (doc) |
| LU-DOCS-2 | `tests/docs.rs::no_doc_says_what_is_no_longer_true` (integration)<br>`tests/docs.rs::the_docs_state_what_an_app_must_know` (integration)<br>`tests/docs.rs::the_readme_installs_this_version_and_its_aar` (integration) |
| LU-V2-COMPAT-1 | `tests/presentation.rs::schema_one_keeps_its_exact_wire_shape` (integration)<br>`tests/presentation.rs::schema_versions_cannot_mix_legacy_and_rich_presentations` (integration) |
| LU-V2-MODEL-1 | `tests/presentation.rs::schema_versions_cannot_mix_legacy_and_rich_presentations` (integration) |
| LU-V2-MODEL-2 | `tests/presentation.rs::every_system_style_has_a_stable_tagged_shape_and_template` (integration) |
| LU-V2-PROGRESS-1 | `tests/presentation.rs::rich_progress_accepts_the_complete_shape_and_rejects_each_structural_boundary` (integration)<br>`tests/presentation.rs::rich_progress_accepts_exact_limits_and_rejects_adjacent_values` (integration) |
| LU-V2-PROGRESS-2 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/RichPresentationInstrumentationTest.kt::progressStyleCarriesSegmentsPointsIconsAndChipText` (device)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/RichPresentationInstrumentationTest.kt::progressStyleOnApi36IsPostedWithoutSemanticStyles` (device) |
| LU-V2-METRIC-1 | `tests/presentation.rs::metrics_are_finite_labeled_and_have_a_valid_critical_index` (integration)<br>`tests/presentation.rs::timer_metric_and_vendor_values_enforce_exact_bounds` (integration) |
| LU-V2-METRIC-2 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/RichPresentationInstrumentationTest.kt::metricStyleCarriesThreeTypedMetricsAndCriticalSelection` (device)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/RichPresentationInstrumentationTest.kt::metricStyleCountsFromAStopwatchStartAndToACountdownEnd` (device) |
| LU-V2-METRIC-3 | `tests/presentation.rs::a_stopwatch_and_a_countdown_metric_are_written_in_the_adapters_names_and_the_earlier_names_are_read` (integration)<br>`tests/bridge.rs::metric_times_are_posted_in_the_adapters_names_and_a_receipt_with_the_earlier_names_is_read` (integration)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/NativeRustInstrumentationTest.kt::stopwatchAndCountdownMetricsCrossRustJniInTheAdaptersNames` (device) |
| LU-V2-ACTION-1 | `tests/presentation.rs::actions_and_icon_sources_are_closed_unique_and_bounded` (integration)<br>`tests/presentation.rs::action_icon_and_boolean_wire_boundaries_are_exact` (integration) |
| LU-V2-ACTION-2 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/RichPresentationInstrumentationTest.kt::immutableUniqueActionsAndDismissalsDrainAsSequencedEvents` (device) |
| LU-V2-ICON-1 | `tests/presentation.rs::actions_and_icon_sources_are_closed_unique_and_bounded` (integration)<br>`tests/presentation.rs::action_icon_and_boolean_wire_boundaries_are_exact` (integration) |
| LU-V2-ICON-2 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/RichPresentationInstrumentationTest.kt::appPrivateImagesWorkAndTraversalOrOversizedFilesFailClosed` (device) |
| LU-V2-VALID-1 | `tests/presentation.rs::nested_models_reject_unknown_fields_and_enforce_text_limits` (integration)<br>`tests/presentation.rs::timer_metric_and_vendor_values_enforce_exact_bounds` (integration) |
| LU-V2-VALID-2 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/RichPresentationInstrumentationTest.kt::malformedRichModelsAreRejectedBeforePosting` (device) |
| LU-V2-ADAPTER-1 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/RichPresentationInstrumentationTest.kt::progressStyleCarriesSegmentsPointsIconsAndChipText` (device) |
| LU-V2-ADAPTER-2 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/RichPresentationInstrumentationTest.kt::standardBigTextTimerAndLowerApiFallbackUseOnlySystemTemplates` (device) |
| LU-V2-CAP-1 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/LiveUpdateBridgeInstrumentationTest.kt::capabilitiesReflectTheRunningDevice` (device) |
| LU-V2-EVENT-1 | `tests/events.rs::event_wire_shapes_are_strict_stable_and_javascript_safe` (integration)<br>`tests/events.rs::event_validation_checks_exact_numeric_identifier_and_batch_boundaries` (integration)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/RichPresentationInstrumentationTest.kt::eventActivityIdentifierLimitsCountUnicodeCodePoints` (device) |
| LU-V2-EVENT-2 | `tests/events.rs::one_client_atomically_drains_events_and_the_next_drain_is_empty` (integration) |
| LU-V2-EVENT-3 | `tests/events.rs::custom_drivers_keep_source_compatibility_with_an_empty_event_default` (integration) |
| LU-V2-EVENT-4 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/RichPresentationInstrumentationTest.kt::immutableUniqueActionsAndDismissalsDrainAsSequencedEvents` (device)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/NativeRustInstrumentationTest.kt::richPresentationAndActionEventsCrossRustJniUnchanged` (device) |
| LU-V2-EVENT-5 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/RichPresentationInstrumentationTest.kt::eventQueueIsDurableBoundedAndReportsDroppedEntries` (device) |
| LU-V2-EVENT-6 | `android/src/test/java/io/github/eedaegon/liveupdate/LiveUpdateEventsTest.kt::theListenerHearsEachStoredEventUntilItIsCleared` (kotlin)<br>`android/src/test/java/io/github/eedaegon/liveupdate/LiveUpdateEventsTest.kt::aNewListenerReplacesTheOldOne` (kotlin)<br>`android/src/test/java/io/github/eedaegon/liveupdate/LiveUpdateEventsTest.kt::aListenerThatThrowsDoesNotReachTheReceiver` (kotlin)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/RichPresentationInstrumentationTest.kt::theListenerHearsAnActionAndADismissalOnceEachIsStored` (device) |
| LU-V2-SAMSUNG-1 | `android/src/test/java/io/github/eedaegon/liveupdate/SamsungNowBarTest.kt::extrasAreClosedOptInAndApi37Only` (kotlin) |
| LU-V2-SAMSUNG-2 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/RichPresentationInstrumentationTest.kt::samsungNowBarCapabilityAndExtrasAreVendorGated` (device) |
| LU-V2-JNI-1 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/NativeRustInstrumentationTest.kt::richPresentationAndActionEventsCrossRustJniUnchanged` (device) |
| LU-V2-COMPAT-2 | `tests/presentation.rs::a_presentation_without_the_optional_members_keeps_its_wire_shape` (integration)<br>`tests/extensions.rs::links_have_a_stable_wire_shape_and_are_left_out_when_unset` (integration) |
| LU-V2-CAP-2 | `tests/contract.rs::an_extension_a_reply_does_not_name_is_not_available` (integration)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/LiveUpdateBridgeInstrumentationTest.kt::capabilitiesReflectTheRunningDevice` (device) |
| LU-V2-CHRONO-1 | `tests/presentation.rs::a_chronometer_runs_beside_every_style_but_the_timer` (integration)<br>`tests/presentation.rs::a_chronometer_has_the_timers_bounds_and_wire_shape` (integration) |
| LU-V2-CHRONO-2 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/RichPresentationInstrumentationTest.kt::aChronometerCountsInTheHeaderBesideAStyle` (device)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/RichPresentationInstrumentationTest.kt::aChronometerTheContractRefusesPostsNothing` (device) |
| LU-V2-LINK-1 | `tests/extensions.rs::a_link_is_an_absolute_printable_ascii_url_with_an_allowed_lowercase_scheme` (integration)<br>`tests/extensions.rs::links_have_a_stable_wire_shape_and_are_left_out_when_unset` (integration)<br>`android/src/test/java/io/github/eedaegon/liveupdate/LinksTest.kt::aLinkIsAnAbsolutePrintableAsciiUrlWithAnAllowedLowercaseScheme` (kotlin) |
| LU-V2-LINK-2 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/ExtensionsInstrumentationTest.kt::theContentTapOpensItsUrlInThePackageAndNamesTheActivity` (device) |
| LU-V2-LINK-3 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/ExtensionsInstrumentationTest.kt::anActionWithAUrlOpensItAndQueuesNoEvent` (device) |
| LU-V2-LINK-4 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/ExtensionsInstrumentationTest.kt::aUrlThatNothingOpensOrTheContractRefusesPostsNothing` (device) |
| LU-V2-LINK-5 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/ExtensionsInstrumentationTest.kt::anOpenedIntentBecomesOneEventOfAStoredActivity` (device) |
| LU-V2-EVENT-7 | `tests/extensions.rs::an_opened_event_names_its_action_only_when_one_opened_the_application` (integration) |
| LU-V2-MODEL-3 | `tests/extensions.rs::a_schema_two_update_may_change_the_style_and_the_record_follows_it` (integration)<br>`tests/extensions.rs::a_notification_that_still_shows_the_previous_style_is_awaited` (integration)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/ExtensionsInstrumentationTest.kt::anUpdateWithAnotherStyleIsBuiltAsThatStyle` (device) |
| LU-END-7 | `tests/extensions.rs::a_delayed_end_posts_the_last_content_in_its_final_form` (integration) |
| LU-END-8 | `tests/extensions.rs::a_delayed_end_does_not_post_what_the_user_removed_and_a_failed_final_post_is_cancelled` (integration) |
| LU-END-9 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/ExtensionsInstrumentationTest.kt::theLastContentStaysUntilItsTimeNoLongerOngoingAndIsNotObserved` (device) |
| LU-FGS-1 | `tests/extensions.rs::a_placement_is_part_of_a_schema_two_start_and_left_out_when_tagged` (integration) |
| LU-FGS-2 | `tests/extensions.rs::the_foreground_placement_needs_its_capability_and_one_live_activity_has_it` (integration) |
| LU-FGS-3 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/ExtensionsInstrumentationTest.kt::aForegroundPlacementIsKeyedWithoutATagAndObservedByItsRecord` (device) |
| LU-FGS-4 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/ExtensionsInstrumentationTest.kt::aServiceAttachesToTheStoredActivityAndKeepsItThroughUpdates` (device) |
| LU-FGS-5 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/ExtensionsInstrumentationTest.kt::endingAHeldActivityReleasesTheServiceAndTellsTheListener` (device) |
| LU-FGS-6 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/ExtensionsInstrumentationTest.kt::detachLeavesAnOrdinaryNotificationAndAttachNeedsALiveForegroundActivity` (device) |
| LU-FGS-7 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/ExtensionsInstrumentationTest.kt::theLastContentOfAHeldActivityReplacesTheServicesNotification` (device) |
| LU-FGS-8 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/ForegroundServiceInstrumentationTest.kt::aPostedNotificationBecomesTheServicesWithoutBeingRemoved` (device)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/ForegroundServiceInstrumentationTest.kt::aPostUnderTheSameIdKeepsTheServiceAndACancelDoesNothing` (device)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/ForegroundServiceInstrumentationTest.kt::leavingTheForegroundRemovesTheNotificationOrLeavesItAnOrdinaryOne` (device)<br>`android/src/androidTest/java/io/github/eedaegon/liveupdate/ForegroundServiceInstrumentationTest.kt::aKilledProcessTakesItsForegroundNotificationWithIt` (device) |
| LU-V2-ALERT-1 | `tests/extensions.rs::an_alert_is_a_flag_of_one_snapshot_and_is_not_carried_to_the_next` (integration) |
| LU-V2-ALERT-2 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/ExtensionsInstrumentationTest.kt::anAlertingRevisionIsPostedOnTheAlertChannelAndTheNextReturnsToTheQuietOne` (device) |
| LU-V2-PUBLIC-1 | `tests/extensions.rs::a_public_version_is_bounded_and_needs_a_private_activity` (integration)<br>`tests/extensions.rs::an_update_cannot_give_a_public_version_to_an_activity_that_is_not_private` (integration) |
| LU-V2-PUBLIC-2 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/ExtensionsInstrumentationTest.kt::aPrivateActivityCarriesItsPublicVersion` (device) |
| LU-V2-BRAND-1 | `tests/extensions.rs::branding_takes_a_resource_icon_a_color_and_a_short_text` (integration) |
| LU-V2-BRAND-2 | `android/src/androidTest/java/io/github/eedaegon/liveupdate/ExtensionsInstrumentationTest.kt::brandingSetsTheSmallIconTheColorAndTheSubText` (device) |
| LU-LIST-10 | `tests/extensions.rs::a_receipt_of_the_other_generation_from_an_earlier_release_is_read_and_replaced` (integration) |
| LU-START-8 | `tests/lifecycle.rs::a_start_the_adapter_refuses_as_invalid_leaves_no_receipt` (integration)<br>`tests/lifecycle.rs::a_retried_start_the_adapter_then_refuses_is_removed_unless_it_was_updated` (integration)<br>`tests/lifecycle.rs::a_refusal_whose_receipts_cannot_be_restored_is_met_again` (integration)<br>`tests/extensions.rs::a_refused_foreground_start_does_not_hold_the_placement` (integration)<br>`tests/bridge.rs::a_post_the_adapter_refuses_over_the_bridge_holds_neither_the_key_nor_the_revision` (integration) |
| LU-UPDATE-9 | `tests/lifecycle.rs::an_update_the_adapter_refuses_as_invalid_restores_the_accepted_snapshot` (integration)<br>`tests/bridge.rs::a_post_the_adapter_refuses_over_the_bridge_holds_neither_the_key_nor_the_revision` (integration) |
| LU-STATE-1 | `tests/state.rs::each_state_of_an_activity_is_answered_once` (integration) |
| LU-STATE-2 | `tests/state.rs::an_unknown_state_is_not_answered_and_hides_nothing` (integration) |
| LU-STATE-3 | `tests/state.rs::a_new_client_continues_and_an_earlier_receipt_is_adopted_unanswered` (integration) |
| LU-STATE-4 | `tests/state.rs::the_state_last_answered_is_stored_and_never_posted` (integration) |
| LU-STATE-5 | `tests/state.rs::a_store_that_fails_answers_nothing_and_loses_nothing` (integration) |
| LU-STATE-6 | `tests/state.rs::the_next_check_is_now_for_a_due_answer_soon_for_an_awaited_post_then_the_earliest_stale_time` (integration) |
