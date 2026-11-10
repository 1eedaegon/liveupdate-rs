# Automated releases: system design

## Components

- `tools/release.sh`: version/commit classification plus fixed-path repository transforms.
- `tests/release/run.sh`: isolated Git-fixture coverage of classification, version transitions,
  changelog generation, and file transforms.
- `.github/workflows/auto-release.yml`: trusted post-CI coordinator with serialized writes.
- `.github/workflows/publish.yml`: validates the tag and creates the GitHub release from changelog
  notes; crates.io publishing stays manual.
- `tests/ci/run.sh`: structural security and recursion checks for both workflows.

## States

- `none`: no release-bearing commit exists after the latest tag.
- `prepare`: generate a patch, minor, or manually requested major release commit.
- `tag`: the working manifest is ahead of the previous tag by the expected transition and has passed
  CI; create the stable tag.
- `blocked`: a breaking or malformed commit requires a human decision or correction.

All version transitions are strict SemVer triples. Existing prerelease tags are ignored when finding
the stable baseline.
