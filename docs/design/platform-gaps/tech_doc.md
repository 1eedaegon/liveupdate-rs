# Technical design

- `src/presentation.rs`: `AndroidPresentation::local_only`; `src/contract.rs`: `local_only` capability;
  `src/client.rs`: refused without it, as targets are.
- `src/driver.rs`: `Driver::open_promotion_settings` with a default; `src/bridge.rs`: the `settings`
  request; `src/client.rs`: `LiveUpdate::open_promotion_settings`.
- AAR: `NotificationRenderer` (`setLocalOnly`), `LiveUpdateProtocol` (`settings`), `AndroidAdapter`
  (`openSettings`, the capability, channel names from resources), `res/values/strings.xml`.
- Tests first: Rust JSON, refusal and the bridge's request; device: `FLAG_LOCAL_ONLY`, the settings screen
  resumed, the channel names from the test package's own strings; the consumer application names its
  channels and `consumer.sh run` reads them.
