# Automated releases: product requirements

## Goal

Turn conventional one-line commit subjects on `main` into deterministic non-major releases. A
breaking release must always be started manually.

## Requirements

- `feat` selects a minor release.
- `fix`, `perf`, `refactor`, `docs`, and `revert` select a patch release.
- `test`, `ci`, `chore`, `build`, and `style` do not select a release by themselves.
- The highest release level since the previous stable tag wins.
- `!`, `BREAKING CHANGE:`, and `BREAKING-CHANGE:` stop automatic release preparation.
- A manually dispatched major release advances `N.x.y` to `N+1.0.0`.
- Unknown non-merge commit subjects fail validation instead of being silently omitted.
- Release notes contain one bullet for each non-merge, non-generated commit subject.
- A tag is created only for a commit whose exact SHA has passed the `CI` workflow on `main`.
- A validated tag automatically publishes its matching crate through crates.io Trusted Publishing.
- Recovery of missing GitHub release assets never republishes an immutable crate version.

## Non-goals

- Supporting prerelease identifiers.
- Inferring breaking changes from source code.
