# Automated releases: architecture

The automation is split around the existing `CI` trust boundary.

1. A successful `CI` run on the current tip of `main` starts the release workflow.
2. If the manifest version equals the latest stable tag, commit subjects select `none`, `patch`, or
   `minor`. A selected release updates the manifests, locks, README, and changelog in one generated
   release commit.
3. The workflow explicitly dispatches `CI` for that release commit. GitHub deliberately does not
   create another workflow run for a push made with `GITHUB_TOKEN`.
4. After the release commit passes `CI`, the workflow creates the matching tag and explicitly
   dispatches the existing release workflow.
5. The release workflow extracts the matching changelog section as its GitHub release notes. Its
   crates.io job retains its manual input, repository variable, environment approval, and OIDC
   boundaries.

The write-capable workflow accepts only successful `push` and `workflow_dispatch` CI runs, checks
out their exact SHA, and verifies that SHA is still the tip of `origin/main` before changing state.
