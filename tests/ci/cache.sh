#!/bin/sh
# What CI keeps between runs (.github/workflows/ci.yml): the keys of its Android caches, and records of
# emulator suites that passed.
#
#   cache.sh keys                 Writes sdk=KEY (the cache of the Android SDK packages the runner lacks),
#                                 avd=KEY (the cache of the system image and the AVD) and emulator=NAME
#                                 (the record that a pass of the emulator suite for these exact inputs
#                                 leaves).
#   cache.sh files                Prints the tracked files the emulator suite reads.
#   cache.sh passed NAME          Looks for the record NAME that a run of this repository's ci.yml left
#                                 and prints that run. Writes found=true or found=false.
#   cache.sh record FILE          Writes the record of this run's pass, with the suite's summary FILE
#                                 (tests/android/run.sh leaves it only after a full pass), to
#                                 $RUNNER_TEMP/passed-emulator/. Without FILE nothing is written. Writes
#                                 recorded=true or recorded=false.
#
# Outputs go to $GITHUB_OUTPUT (standard output when it is not set). The inputs of the emulator suite are
# the files it reads (nothing generated or ignored counts), the Rust toolchain, the
# NDK, cargo-ndk, the JDK and Gradle, the Android API and system image, the SDK cache's key, and the
# runner's system, architecture and image ($ImageOS, $ImageVersion).
#
# A record is an artifact, not a cache: a push to main cannot restore the caches of a pull request, but it
# can find the artifact that the pull request's run left. Only runs of this repository's ci.yml count, not
# runs of a fork.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
# Raise to start every key and record afresh.
version=1
temp=${RUNNER_TEMP:-${TMPDIR:-/tmp}}
image_tag=google_apis

put() { printf '%s\n' "$1" >>"${GITHUB_OUTPUT:-/dev/stdout}"; }

# The files of the emulator suite, as "HASH PATH" lines: the crate, the AAR and its Gradle build, the
# native test library, and the scripts that create the emulator and run the suite. Tracked files and
# new files git does not ignore count, with their content in the working tree (in CI, the checkout).
suite_files() {
  git -C "$root" ls-files --cached --others --exclude-standard -- src Cargo.toml Cargo.lock \
    rust-toolchain.toml .cargo android build.gradle.kts settings.gradle.kts gradle.properties tests/android \
    | sort -u | while IFS= read -r path; do
      if [ -f "$root/$path" ]; then printf '%s\n' "$path"; fi
    done >"$temp/suite-paths.txt"
  git -C "$root" hash-object --stdin-paths <"$temp/suite-paths.txt" | paste -d ' ' - "$temp/suite-paths.txt"
}

host_abi() {
  case ${RUNNER_ARCH:-$(uname -m)} in
    ARM64 | arm64 | aarch64) echo arm64-v8a ;;
    *) echo x86_64 ;;
  esac
}

keys() {
  api=${ANDROID_API:?ANDROID_API names the Android API, such as 37.0}
  abi=$(host_abi)
  runner="${RUNNER_OS:-$(uname -s)}-${RUNNER_ARCH:-$(uname -m)}-${ImageOS:-local}"
  list=$(suite_files)
  [ -n "$list" ] || { echo "git lists no files for the emulator suite" >&2; exit 1; }
  files=$(printf '%s\n' "$list" | git hash-object --stdin)
  avd_script=$(git -C "$root" hash-object -- tests/android/avd.sh)
  sdk="android-sdk-v$version-$runner-platforms-android-$api-emulator"
  avd="android-avd-v$version-$runner-android-$api-$image_tag-$abi-$avd_script"
  inputs="version $version
suite emulator
files $files
rust ${TOOLCHAIN:?TOOLCHAIN names the Rust toolchain}
ndk ${NDK:?NDK names the Android NDK version}
cargo-ndk ${CARGO_NDK:?CARGO_NDK names the cargo-ndk version}
java ${JAVA:?JAVA names the JDK} temurin
gradle ${GRADLE:?GRADLE names the Gradle version}
android $api $image_tag $abi
sdk $sdk
runner $runner ${ImageVersion:-local}"
  printf '%s\n' "$inputs" >"$temp/inputs-emulator.txt"
  name=passed-emulator-$(git hash-object --stdin <"$temp/inputs-emulator.txt")
  printf '%s, from:\n%s\n' "$name" "$inputs" >&2
  put "sdk=$sdk"
  put "avd=$avd"
  put "emulator=$name"
}

passed() {
  name=$1
  repository=${GITHUB_REPOSITORY:?GITHUB_REPOSITORY names the repository}
  server=${GITHUB_SERVER_URL:-https://github.com}
  # The records of that name that have not expired, newest first, from runs of this repository and not of
  # a fork.
  if ! records=$(gh api "repos/$repository/actions/artifacts?name=$name&per_page=100" --jq '.artifacts
      | map(select((.expired | not) and .workflow_run.head_repository_id == .workflow_run.repository_id))
      | sort_by(.created_at) | reverse | .[] | "\(.id) \(.workflow_run.id)"'); then
    echo "::warning title=Test results not looked up::GitHub's artifact API failed, so the suite runs"
    put found=false
    return
  fi
  work=$(mktemp -d "$temp/passed.XXXXXX")
  # Word splitting into pairs of artifact and run IDs, which are plain numbers.
  # shellcheck disable=SC2086
  set -- $records
  while [ $# -ge 2 ]; do
    artifact=$1 run=$2
    shift 2
    workflow=$(gh api "repos/$repository/actions/runs/$run" --jq .path) || continue
    [ "$workflow" = .github/workflows/ci.yml ] || continue
    gh api "repos/$repository/actions/artifacts/$artifact/zip" >"$work/record.zip" || continue
    unzip -p "$work/record.zip" passed.txt >"$work/passed.txt" 2>/dev/null || continue
    url=$server/$repository/actions/runs/$run
    echo "$name: the emulator suite passed for these inputs in $url"
    cat "$work/passed.txt"
    echo "::notice title=Passed before::$name: skipped, the emulator suite passed for the same inputs in $url"
    rm -rf "$work"
    put found=true
    put "run=$url"
    return
  done
  rm -rf "$work"
  echo "$name: no run has passed these inputs yet"
  put found=false
}

record() {
  if [ $# -lt 1 ] || [ ! -f "$1" ]; then
    echo "the emulator suite did not pass in full: no record"
    put recorded=false
    return
  fi
  dir=$temp/passed-emulator
  mkdir -p "$dir"
  { cat "$temp/inputs-emulator.txt"
    echo "run ${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-local}/actions/runs/${GITHUB_RUN_ID:-0} attempt ${GITHUB_RUN_ATTEMPT:-1}"
    echo "commit ${GITHUB_SHA:-$(git -C "$root" rev-parse HEAD)} ${GITHUB_REF:-}"
    cat "$1"
  } >"$dir/passed.txt"
  cat "$dir/passed.txt"
  put recorded=true
}

command=${1:-}
[ $# -eq 0 ] || shift
case $command in
  keys) keys ;;
  files) suite_files | cut -d ' ' -f2- ;;
  passed) passed "$1" ;;
  record) record "$@" ;;
  *) echo "usage: cache.sh keys | files | passed NAME | record FILE" >&2; exit 2 ;;
esac
