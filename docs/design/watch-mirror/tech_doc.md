# Technical design

- **Crate**
  - `src/presentation.rs`: `AndroidPresentation::watch_mirror`, and its validation beside `local_only`.
  - `src/contract.rs`: the `watch_mirror` capability.
  - `src/client.rs`: refused without the capability, as `local_only` is.
- **AAR**
  - `AndroidAdapter`:
    - the capability (a phone: not `FEATURE_WATCH`; the constructor takes it for tests);
    - the mirror's channel;
    - `post` decides the mirror, and `cancel` removes it;
    - the set of dismissed mirrors.
  - `NotificationRenderer`: `mirror(record, alertChannelId, mirrorChannelId)`. It shares `build`'s
    parsing, and `applyRich` makes the activity's own notification local-only with the member.
  - `LiveUpdateReceivers`: the mirror's dismissal.
  - `res/values/strings.xml`: the mirror channel's two strings.
- **Tests first**
  - Rust: JSON, the refusal, and the contradiction.
  - Kotlin unit: the protocol carries the member.
  - Device:
    - the mirror's tag, channel and flags;
    - the activity's own notification is local-only;
    - the alerting revision's channel;
    - the end and its time;
    - the update that turns it off;
    - the dismissal;
    - observation;
    - the capability on a phone.
  - The consumer application: a scenario that starts a mirrored ride. `consumer.sh run` reads the mirror
    from 0.10.0.
  - `tests/android/aar.sh`: the new strings.
