# Automated releases: task list

- [x] Define commit-to-version and manual-major contracts.
- [x] Add failing unit and workflow contract tests.
- [x] Implement release planning, repository updates, and note extraction.
- [x] Add the post-CI release workflow and preserve Trusted Publishing boundaries.
- [x] Publish validated tags automatically while keeping missing-release recovery non-publishing.
- [x] Document operator behavior and recovery.
- [x] Run unit, CI-contract, Rust, package, and security reviews.
- [x] Found at the release of 0.4.4: `publish.yml` asked GitHub once for the successful CI run of the
  tag's commit, 32 seconds after that run had ended, and the list did not have it yet. The tag was
  pushed and nothing was published until the failed run was run again. It now asks for two minutes;
  `tests/ci/run.sh` checks that it does, seen failing first.
