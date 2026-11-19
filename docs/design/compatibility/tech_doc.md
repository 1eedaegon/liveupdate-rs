# Technical design

## The AAR

- `NotificationRenderer`: the closed member lists (`requireFields`) are gone. `validateRecordShape`
  reads `promotionRequested` and refuses `reportedState`; `applyLegacy` refuses a presentation;
  `applyRich` refuses a progress and a timer. A snapshot's title and status are read once, by `build`, and handed on. `build` takes
  `postedAt`, now by default.
- `MetricFallback.kt`: `ParsedMetric` and its values, which the renderer reads; `metricLines(metrics,
  locale)`, the lines of text; `metricClock(metrics)`, the first stopwatch or countdown.
- `NotificationRenderer.applyMetricText`: below API 37, the lines in a `BigTextStyle` and the clock
  where `postsClock` allows it.
- `AndroidAdapter.kt`: `postsClock(sdk, age, margin)`, the one rule of a clock's age, which
  `refuseOldTime` and the renderer both ask; `POST_MARGIN` and `FALLBACK_CLOCK_MARGIN`. `build(record,
  postedAt)`; `schedule` builds as of the start time.
- `IconResolver`: reads the kind and the value, and no closed list.
- `LiveUpdateProtocol`: a command's own member must be there (`requireMember`); an unknown command
  answers `UNSUPPORTED_FEATURE`.
- `EventStore.validateEvents` and `validateKind`: what is known of an event, and no closed list.
  `append` stores the newest events that fit the bound.
- `ContractValidation.kt`: `exceedsUtf8(text, maximum)`, the size of a text as UTF-8 without encoding
  it, which the queue asks at every append.

## The crate

- `src/event.rs`: `EventKind::Unknown` with `#[serde(other)]`; no `deny_unknown_fields` on
  `EventKind`, `ActivityEvent` and `EventBatch`; `ActivityEvent::new`.
- `src/error.rs`, `src/bridge.rs`: no `deny_unknown_fields` on `Error` and on the envelope.
- `#[non_exhaustive]` on `StartOutcome`, `Fallback`, `Visibility`, `PromotionState`, `SemanticStyle`,
  `IconSource`, `MetricValue`, `PresentationStyle`, `Template`, `Placement`, `EndPolicy` and
  `NextCheck`.
- Constructors: `Timer::new`, `Snapshot::new`, `StartRequest::new` (`src/model.rs`); `BigTextStyle::new`,
  `ProgressSegment::new`, `ProgressPoint::new`, `ProgressStyle::new`, `TimerStyle::new`, `Metric::new`,
  `MetricStyle::new`, `PublicVersion::new`, `SamsungNowBar: Default` (`src/presentation.rs`);
  `Observation::new`, `StartReport::new` (`src/driver.rs`); `Capabilities: Default`
  (`src/contract.rs`).
- The request types keep `deny_unknown_fields`.
- `.cargo/mutants.toml` leaves out three mutants by their line, and the lines moved.
- `examples/delivery.rs`, and with it the README, builds on the constructors.

## Tests

Written first and seen failing, but for 6 and 12, which guard what already held.

Rust, `tests/compat.rs` (1 to 3) and `tests/api.rs` (4 and 5):

1. An envelope and an error with more members are read, and the status decides what a reply with
   both a `data` and an `error` is; without a member they need, with a code or a status that is not
   known, they are not.
2. A batch of four events, one of a kind that is not known and three with more members, is read in
   its order; `Unknown` is written under a name of its own and validates.
3. What is known of an event is still checked: an action without its id, a kind without a name, a
   sequence that repeats.
4. A `_` arm after every variant of each of the fifteen enums compiles with `unreachable_patterns`
   denied: it would be unreachable for an enum that cannot gain a variant. And no `pub enum` in `src`
   is without the attribute, so a sixteenth is not forgotten. Both seen failing for one enum closed
   again.
5. Each constructor equals the literal with every optional member unset. `StartRequest::new` gives
   schema version 1 with a progress or a timer and 2 with each style, and the template of each; a
   snapshot that shows nothing or two things is built and refused by `validate`. What they build is
   written as the smallest JSON.

Kotlin, on the JVM:

6. `LiveUpdateProtocolTest`: a payload without its member is refused.
7. `LiveUpdateProtocolTest`: every command takes a payload with one more member, and three commands
   that are not known answer `UNSUPPORTED_FEATURE` and reach no adapter.
8. `MetricFallbackTest`: the lines in two locales, a decimal with its float's digits, the first clock,
   and the rule of a clock's age at its edges. `ContractValidationTest`: the measure against the
   encoder, for texts of one to four bytes a char and half pairs, at and around each bound.

On the device:

9. `CompatibilityInstrumentationTest`: a record with one more member in every object, of both schema
   versions and every style, is posted with what it has, and the record inside the notification and
   the observed snapshot have the members.
10. The same class: what is known is checked as before, seventeen records that are each refused,
    and the record they are made of is posted.
11. The same class: a stored queue with a later kind and later members is drained as it is, behind
    it the next event; seven stored events that break what is known fail. Three large events ten
    bytes below the bound and one more: the oldest goes and is counted.
12. `ScheduledStartInstrumentationTest`: a metric's stopwatch far ahead is scheduled at API 35.
13. `RichPresentationInstrumentationTest`: at API 26, 34 and 36 the lines, the status, the category
    and the clock; clocks alone; the presentation's own chronometer; no clock.
14. The same class: a clock of 15 days is there at API 34 and left out at API 35 and 36, by the built
    notification; at API 35 the metrics are posted without it, and the presentation's own chronometer
    of that age is refused.

Existing tests that asserted a refusal of an unknown member assert the reading now: an event's
`payload` and `url`, an error's `cause`, a public version's `icon`, a chronometer's `paused`, the
dispatcher's `extra`.
