//! The lifecycle's own rules, below the public API: how receipts are bounded and ordered, what an
//! observation may say, and how one record moves between states.

use super::*;
use crate::{
    AndroidPresentation, BigTextStyle, PresentationCapabilities, PresentationStyle, Template,
    Visibility,
};
use std::sync::Mutex;

#[derive(Default)]
struct Storage {
    written: Mutex<Vec<Vec<DriverRecord>>>,
    observed: Mutex<Vec<Observation>>,
    cancelled: Mutex<Vec<String>>,
}

impl Driver for Storage {
    fn capabilities(&self) -> Result<Capabilities> {
        Ok(Capabilities {
            supported: true,
            notifications_enabled: true,
            promotion_enabled: true,
            presentation: PresentationCapabilities::default(),
        })
    }
    fn read(&self) -> Result<Vec<DriverRecord>> {
        Ok(self
            .written
            .lock()
            .unwrap()
            .last()
            .cloned()
            .unwrap_or_default())
    }
    fn write(&self, records: &[DriverRecord]) -> Result<()> {
        self.written.lock().unwrap().push(records.to_vec());
        Ok(())
    }
    fn observe(&self) -> Result<Vec<Observation>> {
        Ok(self.observed.lock().unwrap().clone())
    }
    fn post(&self, _record: &DriverRecord) -> Result<()> {
        Ok(())
    }
    fn cancel(&self, id: &str) -> Result<()> {
        self.cancelled.lock().unwrap().push(id.into());
        Ok(())
    }
}

fn snapshot(revision: u64) -> Snapshot {
    Snapshot {
        revision,
        title: "Delivery".into(),
        status: "Preparing".into(),
        progress: Some(0.25),
        timer: None,
        stale_at: None,
        presentation: None,
    }
}

fn record(id: &str, state: ActivityState) -> DriverRecord {
    DriverRecord {
        reported_state: None,
        dismiss_at: None,
        id: id.into(),
        origin: StartRequest {
            placement: crate::Placement::Tagged,
            start_at: None,
            activity_key: format!("key:{id}"),
            schema_version: 1,
            template: Template::Progress,
            fallback: Fallback::Notification,
            visibility: Visibility::Private,
            snapshot: snapshot(0),
        },
        snapshot: snapshot(0),
        state,
        promotion_requested: false,
        promotion_state: PromotionState::NotApplicable,
        awaiting_observation: false,
        post_failed: false,
    }
}

/// A record of schema version 2 whose request has the text `request` and whose latest content the
/// text `latest`.
fn text_record(id: &str, state: ActivityState, request: &str, latest: &str) -> DriverRecord {
    let content = |text: &str| Snapshot {
        progress: None,
        presentation: Some(AndroidPresentation::new(PresentationStyle::BigText(
            BigTextStyle {
                text: text.into(),
                summary: None,
            },
        ))),
        ..snapshot(0)
    };
    let record = record(id, state);
    DriverRecord {
        origin: StartRequest {
            schema_version: 2,
            template: Template::BigText,
            snapshot: content(request),
            ..record.origin
        },
        snapshot: content(latest),
        ..record
    }
}

/// The bytes the adapter stores for `records`: Android's JSON writes `/` as `\/`.
fn adapter_bytes(records: &[DriverRecord]) -> usize {
    serde_json::to_string(records)
        .unwrap()
        .replace('/', "\\/")
        .len()
}

/// A live receipt and the tombstones `old`, `t0` and `t1`, the oldest first, which the adapter
/// stores in exactly `bytes`.
fn receipts_of(bytes: usize) -> Vec<DriverRecord> {
    let states = [
        ActivityState::Active,
        ActivityState::Ended,
        ActivityState::Dismissed,
        ActivityState::Ended,
    ];
    receipts_in(bytes, states)
}

/// The receipts `live`, `old`, `t0` and `t1` in `states`, which the adapter stores in exactly
/// `bytes`.
fn receipts_in(bytes: usize, states: [ActivityState; 4]) -> Vec<DriverRecord> {
    let with = |old: &str| {
        vec![
            text_record("live", states[0], "now", "now"),
            text_record("old", states[1], "then", old),
            text_record("t0", states[2], "then", "then"),
            text_record("t1", states[3], "then", "then"),
        ]
    };
    // One character of the second receipt's text is one byte.
    let records = with(&"o".repeat(1 + bytes - adapter_bytes(&with("o"))));
    assert_eq!(adapter_bytes(&records), bytes);
    records
}

fn observation(record: &DriverRecord) -> Observation {
    Observation {
        id: record.id.clone(),
        snapshot: record.snapshot.clone(),
        state: ActivityState::Active,
        promotion_requested: record.promotion_requested,
        promotion_state: if record.promotion_requested {
            PromotionState::Promoted
        } else {
            PromotionState::NotApplicable
        },
    }
}

fn ids(records: &[DriverRecord]) -> Vec<&str> {
    records.iter().map(|record| record.id.as_str()).collect()
}

// LU-LIMIT-2
#[test]
fn save_keeps_active_records_first_and_the_newest_128_tombstones() {
    let storage = Storage::default();
    let mut records: Vec<_> = (0..130)
        .map(|index| record(&format!("t{index}"), ActivityState::Ended))
        .collect();
    records.insert(5, record("a", ActivityState::Active));
    records.push(record("b", ActivityState::Unknown));

    let kept = save(&storage, &records).unwrap();

    assert_eq!(kept.len(), 2 + 128);
    assert_eq!(ids(&kept)[..3], ["a", "b", "t2"]);
    assert_eq!(kept.last().unwrap().id, "t129");
    assert_eq!(storage.written.lock().unwrap()[0], kept);
}

// LU-LIMIT-2
#[test]
fn a_record_that_turns_terminal_becomes_the_newest_tombstone() {
    let storage = Storage::default();
    let active = record("active", ActivityState::Active);
    let mut records = vec![active.clone(), record("old", ActivityState::Dismissed)];
    storage.write(&records).unwrap();

    let reconciled = settle(&storage, records.clone(), false).unwrap();
    assert_eq!(ids(&reconciled), ["old", "active"]);
    assert_eq!(reconciled[1].state, ActivityState::Dismissed);

    records = vec![active, record("old", ActivityState::Ended)];
    let ended = DriverRecord {
        state: ActivityState::Ended,
        ..records[0].clone()
    };
    assert_eq!(ids(&retire(&records, &ended)), ["old", "active"]);
}

/// A limit of the size of a few receipts: the rules are the same at any size, and the tests of the
/// public API run at the real one.
const LIMIT: usize = 4_000;

// LU-LIMIT-5
#[test]
fn receipts_of_the_limit_are_kept_and_a_byte_more_drops_the_oldest_tombstone() {
    assert_eq!(STORE_LIMIT, 1_000_000);
    let exact = receipts_of(LIMIT);
    assert_eq!(fitting(&exact, LIMIT).unwrap(), exact);

    let over = receipts_of(LIMIT + 1);
    let kept = fitting(&over, LIMIT).unwrap();
    assert_eq!(ids(&kept), ["live", "t0", "t1"]);

    // The tombstones alone, as an array of their own, to the byte.
    let alone = &exact[1..];
    let bytes = adapter_bytes(alone);
    assert_eq!(fitting(alone, bytes).unwrap(), alone);
    assert_eq!(ids(&fitting(alone, bytes - 1).unwrap()), ["t0", "t1"]);
}

// LU-LIMIT-5
#[test]
fn a_slash_takes_two_bytes_of_the_store() {
    let mut records = receipts_of(LIMIT);
    let json = serde_json::to_string(&records).unwrap().len();
    // The same JSON to the byte, and one byte more as the adapter stores it.
    records[0].snapshot.status = "/reparing".into();
    assert_eq!(serde_json::to_string(&records).unwrap().len(), json);
    assert_eq!(adapter_bytes(&records), LIMIT + 1);

    assert_eq!(
        ids(&fitting(&records, LIMIT).unwrap()),
        ["live", "t0", "t1"]
    );
    assert_eq!(fitting(&records, LIMIT + 1).unwrap(), records);
}

// LU-LIMIT-5
#[test]
fn the_tombstones_that_stay_are_the_newest_run() {
    let mut records = receipts_of(LIMIT);
    // Older than the three that fit: a small one that would fit too, and after it a large one that
    // does not.
    let small = text_record("small", ActivityState::Ended, "then", "then");
    let room = adapter_bytes(std::slice::from_ref(&small));
    let text = "x".repeat(4_000);
    records.insert(1, small);
    records.insert(2, text_record("large", ActivityState::Ended, &text, &text));
    assert_eq!(ids(&records), ["live", "small", "large", "old", "t0", "t1"]);

    let kept = fitting(&records, LIMIT + room).unwrap();

    assert_eq!(ids(&kept), ["live", "old", "t0", "t1"]);
}

// LU-LIMIT-5
#[test]
fn live_receipts_of_the_limit_have_room_and_a_byte_more_has_none() {
    let live = [ActivityState::Active; 4];
    let tombstone = text_record("gone", ActivityState::Ended, "then", "then");

    // A tombstone makes room, so it does not count.
    let exact = receipts_in(LIMIT, live);
    let with_tombstone: Vec<_> = exact.iter().cloned().chain([tombstone]).collect();
    assert_eq!(fitting(&with_tombstone, LIMIT).unwrap(), exact);

    let error = fitting(&receipts_in(LIMIT + 1, live), LIMIT).unwrap_err();
    assert_eq!(error.code, ErrorCode::SystemLimit);
    assert_eq!(error.message, "The live activities are too large to store.");
}

// LU-LIMIT-3
#[test]
fn stored_records_are_checked_before_use() {
    let active: Vec<_> = (0..32)
        .map(|index| record(&format!("a{index}"), ActivityState::Active))
        .collect();
    let tombstones: Vec<_> = (0..128)
        .map(|index| record(&format!("t{index}"), ActivityState::Ended))
        .collect();
    let full: Vec<_> = active.iter().chain(&tombstones).cloned().collect();
    assert_eq!(checked_records(full.clone()).unwrap().len(), 160);

    let mut too_many_active = full.clone();
    too_many_active.push(record("a32", ActivityState::Unknown));
    let mut too_many_tombstones = full.clone();
    too_many_tombstones.push(record("t128", ActivityState::Dismissed));
    let mut duplicate = full.clone();
    duplicate[1].id = "a0".into();
    let mut blank = full.clone();
    blank[0].id = "\u{a0}".into();
    let mut stale_origin = full.clone();
    stale_origin[0].origin.activity_key = "-bad".into();
    let mut timer = full;
    timer[0].snapshot.progress = None;
    timer[0].snapshot.timer = Some(crate::Timer {
        started_at: 1,
        ends_at: 2,
    });
    for corrupt in [
        too_many_active,
        too_many_tombstones,
        duplicate,
        blank,
        stale_origin,
        timer,
    ] {
        assert_eq!(checked_records(corrupt).unwrap_err(), Error::native());
    }
}

// LU-LIMIT-4, LU-LIST-6
#[test]
fn observations_are_bounded_unique_and_active_or_stale() {
    let observed: Vec<_> = (0..ACTIVE_LIMIT + TOMBSTONE_LIMIT)
        .map(|index| observation(&record(&format!("o{index}"), ActivityState::Active)))
        .collect();
    assert_eq!(checked_observations(observed.clone()).unwrap().len(), 160);

    let mut too_many = observed.clone();
    too_many.push(observation(&record("o160", ActivityState::Active)));
    let mut duplicate = observed.clone();
    duplicate[1].id = "o0".into();
    let mut blank = observed.clone();
    blank[0].id = String::new();
    let mut invalid = observed.clone();
    invalid[0].snapshot.title = " ".into();
    let mut corrupt = vec![too_many, duplicate, blank, invalid];
    for state in [
        ActivityState::Unknown,
        ActivityState::Ended,
        ActivityState::Dismissed,
    ] {
        let mut terminal = observed.clone();
        terminal[0].state = state;
        corrupt.push(terminal);
    }
    for observations in corrupt {
        assert_eq!(
            checked_observations(observations).unwrap_err(),
            Error::native()
        );
    }
}

// LU-LIST-6, LU-CAP-4
#[test]
fn an_observation_must_match_the_promotion_and_the_template_of_its_receipt() {
    let plain = record("id", ActivityState::Active);
    let promoted = DriverRecord {
        promotion_requested: true,
        promotion_state: PromotionState::Promoted,
        ..plain.clone()
    };
    for (receipt, requested, state, accepted) in [
        (&plain, false, PromotionState::NotApplicable, true),
        (&plain, false, PromotionState::NotPromoted, false),
        (&plain, true, PromotionState::Promoted, false),
        (&promoted, true, PromotionState::Promoted, true),
        (&promoted, true, PromotionState::NotPromoted, true),
        (&promoted, true, PromotionState::Unknown, true),
        (&promoted, true, PromotionState::NotApplicable, false),
        (&promoted, false, PromotionState::NotApplicable, false),
    ] {
        let native = Observation {
            promotion_requested: requested,
            promotion_state: state,
            ..observation(receipt)
        };
        assert_eq!(
            validate_observation(receipt, &native).is_ok(),
            accepted,
            "{requested} {state:?}"
        );
    }
    let mut timer = observation(&plain);
    timer.snapshot.progress = None;
    timer.snapshot.timer = Some(crate::Timer {
        started_at: 1,
        ends_at: 2,
    });
    assert!(validate_observation(&plain, &timer).is_err());
}

// LU-LIST-2, LU-LIST-3, LU-LIST-4, LU-LIST-5
#[test]
fn one_record_moves_between_states_by_what_android_shows() {
    let awaiting = DriverRecord {
        state: ActivityState::Unknown,
        awaiting_observation: true,
        post_failed: true,
        snapshot: snapshot(2),
        ..record("id", ActivityState::Unknown)
    };
    let shown = observation(&awaiting);
    let observed = reconcile_record(&awaiting, Some(&shown), None).unwrap();
    assert_eq!(observed.state, ActivityState::Active);
    assert!(!observed.awaiting_observation && !observed.post_failed);

    let stale = Observation {
        state: ActivityState::Stale,
        ..shown.clone()
    };
    assert_eq!(
        reconcile_record(&awaiting, Some(&stale), None)
            .unwrap()
            .state,
        ActivityState::Stale
    );
    let older = Observation {
        snapshot: snapshot(1),
        ..shown.clone()
    };
    assert_eq!(
        reconcile_record(&awaiting, Some(&older), None).unwrap(),
        awaiting
    );
    let newer = Observation {
        snapshot: snapshot(3),
        ..shown
    };
    assert!(reconcile_record(&awaiting, Some(&newer), None).is_err());
    assert!(reconcile_record(&observed, Some(&older), None).is_err());

    assert_eq!(reconcile_record(&awaiting, None, None).unwrap(), awaiting);
    for state in [ActivityState::Active, ActivityState::Stale] {
        let gone = reconcile_record(
            &DriverRecord {
                state,
                ..observed.clone()
            },
            None,
            None,
        )
        .unwrap();
        assert_eq!(gone.state, ActivityState::Dismissed);
    }
    for state in [ActivityState::Ended, ActivityState::Dismissed] {
        let terminal = DriverRecord {
            state,
            ..observed.clone()
        };
        assert_eq!(reconcile_record(&terminal, None, None).unwrap(), terminal);
        assert_eq!(
            reconcile_record(&terminal, Some(&older), None).unwrap(),
            terminal
        );
    }
}

// LU-UPDATE-2, LU-UPDATE-3, LU-UPDATE-4, LU-UPDATE-5
#[test]
fn a_snapshot_replaces_the_accepted_one_only_with_a_higher_revision() {
    let accepted = DriverRecord {
        snapshot: snapshot(5),
        ..record("id", ActivityState::Active)
    };
    assert!(replaces(&accepted, &snapshot(6)).unwrap());
    assert!(replaces(&accepted, &snapshot(MAX_SAFE_REVISION)).unwrap());
    assert!(!replaces(&accepted, &snapshot(5)).unwrap());
    assert_eq!(
        replaces(&accepted, &snapshot(4)).unwrap_err().code,
        ErrorCode::StaleRevision
    );
    let other = Snapshot {
        status: "Other".into(),
        ..snapshot(5)
    };
    assert_eq!(
        replaces(&accepted, &other).unwrap_err().code,
        ErrorCode::Conflict
    );
    let timer = Snapshot {
        progress: None,
        timer: Some(crate::Timer {
            started_at: 1,
            ends_at: 2,
        }),
        ..snapshot(6)
    };
    assert_eq!(
        replaces(&accepted, &timer).unwrap_err().code,
        ErrorCode::InvalidInput
    );
}

// LU-UPDATE-6, LU-START-6
#[test]
fn lookups_and_permission_have_their_own_codes() {
    let records = [record("a", ActivityState::Active)];
    assert_eq!(find_record(&records, "a").unwrap().id, "a");
    assert_eq!(
        find_record(&records, "b").unwrap_err().code,
        ErrorCode::NotFound
    );
    let denied = Capabilities {
        supported: true,
        notifications_enabled: false,
        promotion_enabled: true,
        presentation: PresentationCapabilities::default(),
    };
    assert_eq!(
        ensure_permission(denied).unwrap_err().code,
        ErrorCode::PermissionDenied
    );
    assert!(
        ensure_permission(Capabilities {
            notifications_enabled: true,
            ..denied
        })
        .is_ok()
    );
}

// LU-LIST-7, LU-LIST-8
#[test]
fn settle_cancels_ended_notifications_and_ignores_unknown_ones() {
    let storage = Storage::default();
    let ended = record("ended", ActivityState::Ended);
    let mut orphan = observation(&record("orphan", ActivityState::Active));
    orphan.id = "orphan".into();
    *storage.observed.lock().unwrap() = vec![observation(&ended), orphan];

    let settled = settle(&storage, vec![ended.clone()], false).unwrap();

    assert_eq!(settled, [ended]);
    assert_eq!(*storage.cancelled.lock().unwrap(), ["ended"]);
    assert!(storage.written.lock().unwrap().is_empty());
}

const MAX_SAFE_REVISION: u64 = crate::MAX_SAFE_INTEGER;
