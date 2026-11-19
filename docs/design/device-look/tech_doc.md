# Technical design

- `AndroidAdapter.show(tag, id, notification)`: the one place that posts outside a service. Where the
  adapter offers the Ongoing Activity it remembers, in `postedOngoing`, whether what it posts carries
  one, and cancels first when `withdrawsOngoing` says so. `listsOngoing` asks Android, guarded.
  `cancel` and a removal forget the place.
- `WearOngoing.kt`: `WEAR_ONGOING_EXTRA`, the name under which the library keeps its data, and
  `withdrawsOngoing(remembered, carries, listed)`, the rule.
- `src/presentation.rs`: `ProgressStyle::new` sets `styled_by_progress: true`; the member's
  documentation says what false shows. `.cargo/mutants.toml`: the excluded mutant's line moved.
- `MetricFallback.kt`: `metricLines` formats a decimal's float with at most two fraction digits.
- `examples/delivery.rs` and the README no longer set `styled_by_progress`, which the constructor does.
- README: the limits say what One UI and a watch show. `docs/testing.md`: the record of the look.

## Tests

Written first and seen failing.

1. Rust (`tests/api.rs`): the constructor's style is styled by its progress and written with
   `"styledByProgress": true`; with false the member is left out, and JSON without it reads false.
2. Kotlin on the JVM (`MetricFallbackTest`): 3.14159, 0.0004 and 0.33333334 as the S23 showed them,
   2.675 as its float rounds, large numbers with their separators, and no signed zero.
3. Device (`WearOngoingInstrumentationTest`), with a notification listener: carried over carried and
   none over none are replaced in place; off over carried, and the last content over carried, are
   heard as a removal and then a post; an adapter that offers none cancels nothing, and the
   adapter's own cancel queues no event. Seen failing on the watch, where the listener heard three
   posts and no removal. The test of a delayed end's last content (LU-END-9) expects the removal
   where the notification carried an Ongoing Activity.
4. Kotlin on the JVM (`WearOngoingTest`): the rule for every remembered answer, and that Android's
   list is asked only without one.
5. By hand on the watch, with the fix: the icon is there with the Ongoing Activity, gone after an
   update that turns it off, there again, and gone with the last content of an ended activity.
