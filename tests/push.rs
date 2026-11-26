//! An activity begun, updated or ended by a data message from the app's server, run by the lifecycle.

mod common;

use common::{FakeDriver, assert_code, request, snapshot};
use liveupdate::{
    ActivityEvent, ActivityState, AndroidPresentation, BigTextStyle, EndPolicy, ErrorCode,
    EventBatch, EventKind, LiveUpdate, PresentationStyle, Snapshot,
    push::{DATA_KEY, MAX_DATA_BYTES, Payload},
};
use serde_json::{Value, json};
use std::{
    collections::BTreeMap,
    sync::{
        Arc,
        atomic::{AtomicBool, AtomicUsize, Ordering},
    },
    thread,
    time::Duration,
};

fn member(data: &BTreeMap<String, String>) -> Value {
    assert_eq!(data.len(), 1);
    serde_json::from_str(&data[DATA_KEY]).unwrap()
}

// LU-PUSH-1
#[test]
fn a_payload_is_one_member_of_the_data_map_with_its_version_and_command() {
    let start = Payload::start(request("ride:1")).to_data().unwrap();
    let written = member(&start);
    assert_eq!(written["v"], json!(1));
    assert_eq!(written["command"], json!("start"));
    assert_eq!(
        written["request"],
        serde_json::to_value(request("ride:1")).unwrap()
    );

    let update = member(&Payload::update("ride:1", snapshot(3)).to_data().unwrap());
    assert_eq!(
        update,
        json!({ "v": 1, "command": "update", "activityKey": "ride:1",
                "snapshot": serde_json::to_value(snapshot(3)).unwrap() })
    );
    let end = member(
        &Payload::end("ride:1", EndPolicy::Immediate)
            .to_data()
            .unwrap(),
    );
    assert_eq!(
        end,
        json!({ "v": 1, "command": "end", "activityKey": "ride:1", "policy": { "kind": "immediate" } })
    );

    // The device reads what the server wrote, and what a later server adds beside it.
    for payload in [
        Payload::start(request("ride:1")),
        Payload::update("ride:1", snapshot(3)),
        Payload::end("ride:1", EndPolicy::After { at: 5_000 }),
    ] {
        let data = payload.to_data().unwrap();
        assert_eq!(Payload::parse(&data[DATA_KEY]).unwrap(), payload);
        let mut later = member(&data);
        later["sentAt"] = json!(1);
        assert_eq!(Payload::parse(&later.to_string()).unwrap(), payload);
    }
    assert_eq!(
        Payload::update("ride:1", snapshot(3)).activity_key(),
        "ride:1"
    );
    assert_eq!(Payload::start(request("ride:2")).activity_key(), "ride:2");
    assert_eq!(Payload::update("ride:1", snapshot(3)).command(), "update");
    assert_eq!(Payload::update("ride:1", snapshot(3)).revision(), Some(3));
    assert_eq!(
        Payload::end("ride:1", EndPolicy::Immediate).revision(),
        None
    );
}

// LU-PUSH-1
#[test]
fn a_payload_of_another_version_or_command_is_refused() {
    let update = member(&Payload::update("ride:1", snapshot(3)).to_data().unwrap());
    for broken in [
        json!({ "v": 2, "command": "update", "activityKey": "ride:1", "snapshot": update["snapshot"] }),
        json!({ "command": "update", "activityKey": "ride:1", "snapshot": update["snapshot"] }),
        json!({ "v": 1, "command": "pause", "activityKey": "ride:1" }),
        json!({ "v": 1, "command": "update", "snapshot": update["snapshot"] }),
        json!({ "v": 1, "command": "end", "activityKey": "ride:1" }),
        json!([]),
    ] {
        assert_code(Payload::parse(&broken.to_string()), ErrorCode::InvalidInput);
    }
    assert_code(Payload::parse("{"), ErrorCode::InvalidInput);
}

// LU-PUSH-2
#[test]
fn the_server_builds_only_what_the_lifecycle_would_take_and_what_fcm_carries() {
    let mut empty = request("ride:1");
    empty.activity_key = String::new();
    assert_code(Payload::start(empty).to_data(), ErrorCode::InvalidInput);
    let mut untitled = snapshot(1);
    untitled.title = String::new();
    assert_code(
        Payload::update("ride:1", untitled.clone()).to_data(),
        ErrorCode::InvalidInput,
    );
    assert_code(
        Payload::update("", snapshot(1)).to_data(),
        ErrorCode::InvalidInput,
    );
    assert_code(
        Payload::end("ride:1", EndPolicy::After { at: u64::MAX }).to_data(),
        ErrorCode::InvalidInput,
    );
    // The device checks it again: a server is not trusted.
    let mut written = member(&Payload::update("ride:1", snapshot(1)).to_data().unwrap());
    written["snapshot"]["title"] = json!("");
    assert_code(
        Payload::parse(&written.to_string()),
        ErrorCode::InvalidInput,
    );

    // FCM carries 4096 bytes of data, keys and values together.
    assert_eq!(MAX_DATA_BYTES, 4096);
    let long = |length: usize| {
        let style = PresentationStyle::BigText(BigTextStyle::new("a".repeat(length)));
        Payload::update(
            "ride:1",
            Snapshot {
                presentation: Some(AndroidPresentation::new(style)),
                ..Snapshot::new(1, "Ride", "On the way")
            },
        )
    };
    let bytes = |data: &BTreeMap<String, String>| -> usize {
        data.iter()
            .map(|(key, value)| key.len() + value.len())
            .sum()
    };
    // A big text is not empty: one character of it is counted out of what the payload costs around it.
    let overhead = bytes(&long(1).to_data().unwrap()) - 1;
    let fits = long(MAX_DATA_BYTES - overhead).to_data().unwrap();
    assert_eq!(bytes(&fits), MAX_DATA_BYTES);
    let error = long(MAX_DATA_BYTES - overhead + 1).to_data().unwrap_err();
    assert_eq!(error.code, ErrorCode::InvalidInput);
    assert!(error.message.contains("4096"), "{}", error.message);
    // Bytes, not characters: a Korean status of 240 characters is 720 bytes.
    let mut korean = snapshot(1);
    korean.status = "가".repeat(240);
    let data = Payload::update("ride:1", korean).to_data().unwrap();
    assert!(bytes(&data) > 720);
}

// LU-PUSH-3
#[test]
fn a_push_runs_the_lifecycle_by_the_activitys_key() {
    let driver = FakeDriver::default();
    let client = LiveUpdate::new(driver.clone());

    let started = client.push(&Payload::start(request("ride:1"))).unwrap();
    assert_eq!(started.activity_key, "ride:1");
    let updated = client
        .push(&Payload::update("ride:1", snapshot(2)))
        .unwrap();
    assert_eq!(
        (updated.id.as_str(), updated.snapshot.revision),
        (started.id.as_str(), 2)
    );

    // A repeat changes nothing, a stale one is refused, and an unknown key has no activity.
    let (posts, _) = driver.counts();
    assert_eq!(
        client
            .push(&Payload::update("ride:1", snapshot(2)))
            .unwrap(),
        updated
    );
    assert_eq!(driver.counts().0, posts);
    assert_code(
        client.push(&Payload::update("ride:1", snapshot(1))),
        ErrorCode::StaleRevision,
    );
    assert_code(
        client.push(&Payload::update("ride:9", snapshot(3))),
        ErrorCode::NotFound,
    );

    let ended = client
        .push(&Payload::end("ride:1", EndPolicy::Immediate))
        .unwrap();
    assert_eq!(ended.state, ActivityState::Ended);
    // An ended activity is no longer the key's: a later update has none to name.
    assert_code(
        client.push(&Payload::update("ride:1", snapshot(3))),
        ErrorCode::NotFound,
    );
    // The start delivered again, after its end, begins nothing.
    let posts = driver.counts().0;
    let late = client.push(&Payload::start(request("ride:1"))).unwrap();
    assert_eq!(
        (late.id.as_str(), late.state),
        (started.id.as_str(), ActivityState::Ended)
    );
    assert_eq!(driver.counts().0, posts);
    // Another request begins the key again, with a new id.
    let mut next = request("ride:1");
    next.snapshot.status = "Another ride".into();
    let again = client.push(&Payload::start(next)).unwrap();
    assert_ne!(again.id, started.id);
    assert_eq!(again.state, ActivityState::Active);
}

/// A driver that counts the clients inside it at once.
#[derive(Clone)]
struct Watched {
    inner: FakeDriver,
    inside: Arc<AtomicUsize>,
    overlapped: Arc<AtomicBool>,
}

impl Watched {
    fn enter<T>(&self, action: impl FnOnce() -> T) -> T {
        if self.inside.fetch_add(1, Ordering::SeqCst) > 0 {
            self.overlapped.store(true, Ordering::SeqCst);
        }
        thread::sleep(Duration::from_millis(1));
        let answer = action();
        self.inside.fetch_sub(1, Ordering::SeqCst);
        answer
    }
}

impl liveupdate::Driver for Watched {
    fn capabilities(&self) -> liveupdate::Result<liveupdate::Capabilities> {
        self.enter(|| self.inner.capabilities())
    }
    fn read(&self) -> liveupdate::Result<Vec<liveupdate::DriverRecord>> {
        self.enter(|| self.inner.read())
    }
    fn write(&self, records: &[liveupdate::DriverRecord]) -> liveupdate::Result<()> {
        self.enter(|| self.inner.write(records))
    }
    fn observe(&self) -> liveupdate::Result<Vec<liveupdate::Observation>> {
        self.enter(|| self.inner.observe())
    }
    fn post(&self, record: &liveupdate::DriverRecord) -> liveupdate::Result<()> {
        self.enter(|| self.inner.post(record))
    }
    fn cancel(&self, id: &str) -> liveupdate::Result<()> {
        self.enter(|| self.inner.cancel(id))
    }
    fn drain_events(&self) -> liveupdate::Result<EventBatch> {
        self.enter(|| self.inner.drain_events())
    }
}

// LU-PUSH-4
#[test]
fn two_clients_of_one_process_take_turns() {
    let shared = Watched {
        inner: FakeDriver::default(),
        inside: Arc::default(),
        overlapped: Arc::default(),
    };
    // The app's retained client, and the one a push builds.
    let app = Arc::new(LiveUpdate::new(shared.clone()));
    let pushed = Arc::new(LiveUpdate::new(shared.clone()));
    let threads: Vec<_> = (0..8)
        .map(|index| {
            let client = Arc::clone(if index % 2 == 0 { &app } else { &pushed });
            thread::spawn(move || {
                let started = client.start(request("ride:1")).unwrap();
                client.update(&started.id, snapshot(index + 1)).ok();
            })
        })
        .collect();
    for thread in threads {
        thread.join().unwrap();
    }
    assert!(!shared.overlapped.load(Ordering::SeqCst));
    assert!(shared.inner.counts().0 >= 1);
    assert_eq!(app.list().unwrap().len(), 1);
}

// LU-PUSH-5
#[test]
fn what_a_push_did_is_an_event_with_its_command_key_revision_and_code() {
    let done = json!({
        "sequence": 1, "activityId": "a1", "occurredAt": 5,
        "kind": { "kind": "pushed", "command": "update", "activityKey": "ride:1", "revision": 3 }
    });
    let event: ActivityEvent = serde_json::from_value(done.clone()).unwrap();
    assert_eq!(
        event.kind,
        EventKind::Pushed {
            command: "update".into(),
            activity_key: "ride:1".into(),
            revision: Some(3),
            code: None,
        }
    );
    assert_eq!(serde_json::to_value(&event).unwrap(), done);
    event.validate().unwrap();

    // A push that named no activity has no id, which no other kind may lack.
    let refused = json!({
        "sequence": 2, "activityId": "", "occurredAt": 5,
        "kind": { "kind": "pushed", "command": "update", "activityKey": "ride:9", "revision": 3,
                  "code": "NOT_FOUND" }
    });
    let event: ActivityEvent = serde_json::from_value(refused).unwrap();
    event.validate().unwrap();
    assert!(matches!(
        event.kind,
        EventKind::Pushed {
            code: Some(ErrorCode::NotFound),
            ..
        }
    ));
    let dismissed: ActivityEvent = serde_json::from_value(json!({
        "sequence": 3, "activityId": "", "occurredAt": 5, "kind": { "kind": "dismissed" }
    }))
    .unwrap();
    assert_code(dismissed.validate(), ErrorCode::InvalidInput);
    for command in ["pause", ""] {
        let odd: ActivityEvent = serde_json::from_value(json!({
            "sequence": 4, "activityId": "a1", "occurredAt": 5,
            "kind": { "kind": "pushed", "command": command, "activityKey": "ride:1" }
        }))
        .unwrap();
        assert_code(odd.validate(), ErrorCode::InvalidInput);
    }
}

// LU-PUSH-6
#[test]
fn the_adapter_says_whether_a_push_can_reach_it() {
    let capabilities = FakeDriver::default().edit(|state| state.capabilities);
    assert!(!capabilities.presentation.push);
    let written = serde_json::to_value(capabilities).unwrap();
    assert_eq!(written["presentation"]["push"], json!(false));
}
