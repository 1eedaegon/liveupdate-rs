# System design

## Presentation contract

Every v2 snapshot keeps the existing `revision`, `title`, `status`, and optional `staleAt`, then adds:

- one tagged style: `standard`, `bigText`, `progress`, `timer`, or `metric`;
- optional short critical text and large icon;
- zero to three actions with stable IDs, labels, optional resource icons, and authentication policy;
- optional Samsung Now Bar fields from a closed typed object.

Progress uses integer units so segments, points, and progress cannot lose precision crossing JSON.
Each segment/point may have a stable integer ID, ARGB color, and API 37 semantic style. Icons are
typed resource/file references. Metric style has one to three typed values and an optional critical
index.

## Operations

The existing `capabilities`, `start`, `update`, `end`, and `list` operations remain. `events` drains
one durable batch:

1. A notification action or delete intent reaches a non-exported receiver.
2. The receiver validates bounded identifiers and appends a sequenced event under a process-safe
   preferences lock.
3. `events` atomically returns and clears up to the queue limit, plus a dropped-event count.
4. Framework adapters may return no events by default, preserving custom driver compatibility.

## Rendering matrix

| Style | API 26-35 | API 36 | API 37+ |
| --- | --- | --- | --- |
| Standard | Standard | promoted-capable Standard | promoted-capable Standard |
| BigText | BigTextStyle | promoted BigTextStyle | promoted BigTextStyle |
| Progress | classic progress fallback | ProgressStyle | ProgressStyle + semantic styling |
| Timer | chronometer | promoted chronometer | promoted chronometer |
| Metric | standard text fallback | standard text fallback | MetricStyle |

Unsupported visual details are reported in `degradedFeatures`; they do not silently change lifecycle
success. `Fallback::None` still rejects a start when promotion itself is unavailable.

## Event and storage bounds

- At most 32 live activities and 128 tombstones, unchanged.
- At most 128 queued events; overflow drops the oldest and increments a persisted counter.
- At most three actions, three metrics, 32 progress segments, and 32 progress points per snapshot.
- At most one MiB per bridge payload/response and per stored record set.
- Image files are limited before decode and decoded dimensions are bounded.

## Failure behavior

Invalid models fail before driver calls. Missing/invalid resources degrade to a named feature when a
safe fallback exists; path escape, malformed event metadata, or contradictory observations fail
closed with stable errors. Receivers never surface caller content in error messages.
