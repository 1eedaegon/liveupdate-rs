# Automated releases: technical design

`tools/release.sh plan` reads `Cargo.toml`, the greatest stable `vMAJOR.MINOR.PATCH` tag merged into
`HEAD`, and non-merge commits after that tag. It emits action, version, tag, and previous tag through
`GITHUB_OUTPUT`. `prepare` performs strict, count-checked replacements and inserts chronological
commit-subject bullets under `Unreleased`. `notes` extracts exactly one version section.

The workflow has only `contents: write` and `actions: write`. It does not run for pull requests and
does not execute artifacts from another workflow. Every checkout action is commit-pinned. Before a
push or tag it fetches `main` and tags, then requires checked-out `HEAD == origin/main`.

Generated commits use `chore: release vX.Y.Z`. A manual major commit additionally carries
`Release-Mode: manual-major`; the post-CI phase requires that marker for a major transition. Tag and
workflow dispatch operations are idempotent at their boundary: an existing tag must resolve to the
same commit, and the publish workflow safely reuses identical release assets.
