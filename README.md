# liveupdate-rs

Android Live Updates from native Rust.

`liveupdate` is being built as a Rust-first `start` / `update` / `end` library for promoted
ongoing notifications, with a regular ongoing-notification fallback. Android framework integration
stays in a small managed adapter; validation, lifecycle, ordering and the public API stay in Rust.

> Status: repository bootstrap. The shared response contract is implemented. The lifecycle, JNI
> bridge and Android adapter are planned but not implemented yet.

## Architecture

```text
Rust application
    -> liveupdate Rust API and lifecycle
    -> narrow JNI bridge
    -> Android adapter (NotificationManager, PendingIntent, receivers)
    -> Android system UI
```

The adapter uses system notification templates. Promotion is best effort, so successful posting
does not guarantee promoted presentation. Unsupported devices or denied promotion follow the
request's fallback policy.

See the [product requirements](docs/implementation/PRD.md),
[architecture](docs/implementation/architecture.md), [test policy](docs/testing.md), and
[task list](docs/implementation/task_list.md).

## Development

```sh
cargo fmt --all -- --check
cargo clippy --locked --all-targets -- -D warnings
cargo test --locked --all-targets
cargo test --locked --doc
```

## License

[MIT](LICENSE-MIT) or [Apache-2.0](LICENSE-APACHE), at your option.
