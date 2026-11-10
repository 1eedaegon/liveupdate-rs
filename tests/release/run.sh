#!/usr/bin/env bash
# Isolated repository tests for tools/release.sh. No test changes this checkout.
set -euo pipefail

root=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)
source_script=$root/tools/release.sh
work=$(mktemp -d "${TMPDIR:-/tmp}/liveupdate-release.XXXXXX")
trap 'rm -rf "$work"' EXIT HUP INT TERM
tests=0
repo=

fail() {
  echo "FAIL $*" >&2
  exit 1
}

assert_contains() {
  local value=$1 expected=$2
  [[ $value == *"$expected"* ]] || fail "expected $expected in: $value"
}

new_repo() {
  local name=$1
  repo=$work/$name
  mkdir -p "$repo/tools" "$repo/android/native-test"
  cp "$source_script" "$repo/tools/release.sh"
  chmod +x "$repo/tools/release.sh"
  printf '%s\n' \
    '[package]' \
    'name = "liveupdate"' \
    'version = "0.1.3"' \
    '' \
    '[dependencies]' \
    'other = "0.1.3"' > "$repo/Cargo.toml"
  printf '%s\n' \
    'version = 4' \
    '' \
    '[[package]]' \
    'name = "liveupdate"' \
    'version = "0.1.3"' \
    '' \
    '[[package]]' \
    'name = "other"' \
    'version = "0.1.3"' > "$repo/Cargo.lock"
  cp "$repo/Cargo.lock" "$repo/android/native-test/Cargo.lock"
  printf '%s\n' \
    '# liveupdate' \
    'liveupdate = { version = "0.1.3", features = ["android"] }' \
    'Get liveupdate-android-0.1.3.aar.' \
    'implementation(files("libs/liveupdate-android-0.1.3.aar"))' > "$repo/README.md"
  printf '%s\n' \
    '# Changelog' \
    '' \
    '## Unreleased' \
    '' \
    '## 0.1.3 - 2026-11-10' \
    '' \
    '- Initial stable release.' > "$repo/CHANGELOG.md"
  (
    cd "$repo"
    git init -q -b main
    git config user.name tester
    git config user.email tester@example.com
    git config core.editor true
    git add .
    git commit -q -m 'chore: release v0.1.3'
    git tag -a v0.1.3 -m v0.1.3
  )
}

release() {
  (cd "$repo" && tools/release.sh "$@")
}

empty_commit() {
  local subject=$1 body=${2-}
  if [[ -n $body ]]; then
    (cd "$repo" && git commit -q --allow-empty -m "$subject" -m "$body")
  else
    (cd "$repo" && git commit -q --allow-empty -m "$subject")
  fi
}

expect_failure() {
  local expected=$1
  shift
  local output
  if output=$("$@" 2>&1); then
    fail "command unexpectedly passed: $*"
  fi
  assert_contains "$output" "$expected"
}

test_minor_wins() {
  new_repo minor
  empty_commit 'fix: retain event'
  empty_commit 'feat(android): add layout tree'
  empty_commit 'docs: explain layout tree'
  local output
  output=$(release plan)
  assert_contains "$output" 'action=prepare'
  assert_contains "$output" 'version=0.2.0'
  tests=$((tests + 1))
}

test_internal_commits_do_not_release() {
  new_repo internal
  for kind in test ci chore build style; do
    empty_commit "$kind: maintain automation"
  done
  local output
  output=$(release plan)
  assert_contains "$output" 'action=none'
  assert_contains "$output" 'version='
  tests=$((tests + 1))
}

test_breaking_requires_manual_major() {
  new_repo breaking
  empty_commit 'feat(core)!: replace presentation schema' 'BREAKING CHANGE: schema v3 only'
  expect_failure 'manual major release' release plan
  local output
  output=$(release plan --major)
  assert_contains "$output" 'action=prepare'
  assert_contains "$output" 'version=1.0.0'
  new_repo breaking-body
  empty_commit 'fix: replace presentation schema' 'BREAKING-CHANGE: schema v3 only'
  expect_failure 'manual major release' release plan
  tests=$((tests + 1))
}

test_every_patch_type_is_recognized() {
  new_repo patch-types
  for kind in fix perf refactor docs revert; do
    empty_commit "$kind: adjust release behavior"
  done
  local output
  output=$(release plan)
  assert_contains "$output" 'action=prepare'
  assert_contains "$output" 'version=0.1.4'
  tests=$((tests + 1))
}

test_unknown_subject_fails() {
  new_repo unknown
  empty_commit 'update release files'
  expect_failure 'non-conventional subject' release plan
  tests=$((tests + 1))
}

test_prepare_updates_every_contract_file_and_notes() {
  new_repo prepare
  empty_commit 'fix: retain event'
  local first second notes output
  first=$(cd "$repo" && git rev-parse --short=7 HEAD)
  empty_commit 'test: cover retained event'
  second=$(cd "$repo" && git rev-parse --short=7 HEAD)
  release prepare 0.1.4 --date 2026-11-11
  grep -q '^version = "0.1.4"$' "$repo/Cargo.toml" || fail 'manifest was not updated'
  [[ $(grep -c '^version = "0.1.4"$' "$repo/Cargo.lock") == 1 ]] || fail 'root lock was not updated once'
  [[ $(grep -c '^version = "0.1.4"$' "$repo/android/native-test/Cargo.lock") == 1 ]] || fail 'native lock was not updated once'
  [[ $(grep -c 'liveupdate-android-0.1.4.aar' "$repo/README.md") == 2 ]] || fail 'README AARs were not updated'
  grep -q 'version = "0.1.4"' "$repo/README.md" || fail 'README dependency was not updated'
  output=$(cat "$repo/CHANGELOG.md")
  assert_contains "$output" "- fix: retain event (\`$first\`)"
  assert_contains "$output" "- test: cover retained event (\`$second\`)"
  notes=$repo/notes.md
  release notes 0.1.4 > "$notes"
  [[ $(cat "$notes") == "- fix: retain event (\`$first\`)"$'\n'"- test: cover retained event (\`$second\`)" ]] || \
    fail 'release notes did not equal the changelog section'
  tests=$((tests + 1))
}

test_prepared_patch_is_ready_to_tag() {
  new_repo tag-patch
  empty_commit 'fix: retain event'
  release prepare 0.1.4 --date 2026-11-11
  (cd "$repo" && git add . && git commit -q -m 'chore: release v0.1.4')
  local output
  output=$(release plan)
  assert_contains "$output" 'action=tag'
  assert_contains "$output" 'version=0.1.4'
  tests=$((tests + 1))
}

test_prepared_major_requires_marker() {
  new_repo tag-major
  empty_commit 'feat!: schema v3'
  release prepare 1.0.0 --major --date 2026-11-11
  (cd "$repo" && git add . && git commit -q -m 'chore: release v1.0.0')
  expect_failure 'manual major release' release plan
  (cd "$repo" && git commit -q --amend -m 'chore: release v1.0.0' -m 'Release-Mode: manual-major')
  local output
  output=$(release plan)
  assert_contains "$output" 'action=tag'
  assert_contains "$output" 'version=1.0.0'
  tests=$((tests + 1))
}

test_unexpected_version_jump_fails() {
  new_repo jump
  empty_commit 'fix: retain event'
  sed 's/version = "0.1.3"/version = "0.1.5"/' "$repo/Cargo.toml" > "$repo/Cargo.toml.next"
  mv "$repo/Cargo.toml.next" "$repo/Cargo.toml"
  expect_failure 'commit subjects require 0.1.4' release plan
  tests=$((tests + 1))
}

test_failed_validation_does_not_partially_update_files() {
  new_repo atomic
  empty_commit 'fix: retain event'
  grep -v '^Get liveupdate-android' "$repo/README.md" > "$repo/README.md.next"
  mv "$repo/README.md.next" "$repo/README.md"
  expect_failure 'README' release prepare 0.1.4 --date 2026-11-11
  grep -q '^version = "0.1.3"$' "$repo/Cargo.toml" || fail 'a failed prepare partially changed Cargo.toml'
  grep -q '^version = "0.1.3"$' "$repo/Cargo.lock" || fail 'a failed prepare partially changed Cargo.lock'
  tests=$((tests + 1))
}

test_invalid_stable_version_is_rejected() {
  new_repo invalid-version
  empty_commit 'fix: retain event'
  sed 's/version = "0.1.3"/version = "0.01.3"/' "$repo/Cargo.toml" > "$repo/Cargo.toml.next"
  mv "$repo/Cargo.toml.next" "$repo/Cargo.toml"
  expect_failure 'not a stable SemVer triple' release plan
  tests=$((tests + 1))
}

test_merge_commit_is_ignored() {
  new_repo merge
  (cd "$repo" && git switch -q -c topic && git commit -q --allow-empty -m 'test: topic coverage')
  (cd "$repo" && git switch -q main && git commit -q --allow-empty -m 'ci: main maintenance')
  (cd "$repo" && git merge -q --no-ff topic -m 'Merge branch topic')
  local output
  output=$(release plan)
  assert_contains "$output" 'action=none'
  tests=$((tests + 1))
}

test_minor_wins
test_internal_commits_do_not_release
test_breaking_requires_manual_major
test_every_patch_type_is_recognized
test_unknown_subject_fails
test_prepare_updates_every_contract_file_and_notes
test_prepared_patch_is_ready_to_tag
test_prepared_major_requires_marker
test_unexpected_version_jump_fails
test_failed_validation_does_not_partially_update_files
test_invalid_stable_version_is_rejected
test_merge_commit_is_ignored

echo "release automation: $tests isolated scenarios passed"
