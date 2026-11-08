# Technical design

## Packaging

The repository will publish:

- the `liveupdate` Rust crate;
- an Android AAR containing the managed adapter and resources;
- a native Rust Android sample and integration instructions.

The Rust crate is compiled into the host application's native shared library. The AAR supplies
Android components that the framework must instantiate and forwards bounded requests and events
through JNI.

## Contract

Serialized names use `camelCase`. Request decoders reject unknown members. Response models keep
forward compatibility through explicit `unknown` enum states and optional additions. Public
errors have stable codes and sanitized messages; detailed platform causes remain in device logs.

## Android policy

- Use public Android and AndroidX APIs only.
- Use eligible system styles and no custom `RemoteViews`.
- Feature-detect promotion and style support at runtime.
- Treat promotion as best effort.
- Keep minimum SDK independent of compile SDK where public compatibility APIs permit it.

## Quality gates

- Rust: format, Clippy with warnings denied, unit/integration/doc tests and 80% coverage.
- Android: lint, unit tests, instrumentation and 80% coverage.
- Packaging: Cargo dry run and reproducible AAR build.
- Security: no secrets, mutable exported receivers or unvalidated external input.
