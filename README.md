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

Enter the reproducible development shell. It pins Rust 1.96, JDK 21, `cargo-ndk` and the Rust
standard libraries needed by a Galaxy S23 and the API 37 x86_64 emulator. Android Studio manages
the SDK and NDK at their standard host location.

```sh
nix develop
./scripts/check-android-env.sh
cargo ndk -t arm64-v8a -t x86_64 check --locked
```

With `direnv` installed, run `direnv allow` once instead of entering the shell manually.

The environment check expects Android SDK 37.0 and NDK 27.3.13750724. The S23 build verifies the
native `arm64-v8a` artifact; notification behavior remains a fallback-only compatibility pass until
the Android adapter is implemented.

Run the regular Rust quality gates with:

```sh
cargo fmt --all -- --check
cargo clippy --locked --all-targets -- -D warnings
cargo test --locked --all-targets
cargo test --locked --doc
sh tests/android_env.sh
```

## License

[MIT](LICENSE-MIT) or [Apache-2.0](LICENSE-APACHE), at your option.
