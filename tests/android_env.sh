#!/bin/sh

set -eu

PROJECT_ROOT="$(cd -- "$(dirname -- "$0")/.." && pwd)"
readonly PROJECT_ROOT
readonly CHECK_SCRIPT="$PROJECT_ROOT/scripts/check-android-env.sh"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/liveupdate-android-env.XXXXXX")"
readonly TEST_ROOT

cleanup() {
    rm -rf "$TEST_ROOT"
}

trap cleanup EXIT HUP INT TERM

write_fake_command() {
    command_name="$1"
    command_body="$2"
    command_path="$TEST_ROOT/bin/$command_name"

    {
        printf '%s\n' '#!/bin/sh' 'set -eu'
        printf '%s\n' "$command_body"
    } >"$command_path"
    chmod +x "$command_path"
}

prepare_fixture() {
    mkdir -p \
        "$TEST_ROOT/bin" \
        "$TEST_ROOT/jdk/bin" \
        "$TEST_ROOT/sdk/platforms/android-37.0" \
        "$TEST_ROOT/sdk/ndk/27.3.13750724" \
        "$TEST_ROOT/rust/lib/rustlib/aarch64-linux-android/lib" \
        "$TEST_ROOT/rust/lib/rustlib/x86_64-linux-android/lib"

    printf '%s\n' 'Pkg.Revision = 27.3.13750724' \
        >"$TEST_ROOT/sdk/ndk/27.3.13750724/source.properties"

    write_fake_command cargo 'printf "%s\n" "cargo 1.96.0"'
    write_fake_command cargo-ndk 'printf "%s\n" "cargo-ndk 4.1.2"'
    # The variables intentionally expand when the generated fixture executes.
    # shellcheck disable=SC2016
    write_fake_command rustc 'if [ "${1:-}" = "--print" ]; then printf "%s\n" "$FAKE_RUST_SYSROOT"; else printf "%s\n" "rustc 1.96.0 (test)"; fi'
    write_fake_command java 'printf "%s\n" "openjdk version \"21.0.11\"" >&2'
    cp "$TEST_ROOT/bin/java" "$TEST_ROOT/jdk/bin/java"
}

run_check() {
    env \
        ANDROID_HOME="$TEST_ROOT/sdk" \
        FAKE_RUST_SYSROOT="$TEST_ROOT/rust" \
        JAVA_HOME="$TEST_ROOT/jdk" \
        PATH="$TEST_ROOT/bin:/usr/bin:/bin" \
        "$CHECK_SCRIPT" 2>&1
}

assert_contains() {
    output="$1"
    expected="$2"

    if ! printf '%s\n' "$output" | grep -F "$expected" >/dev/null; then
        printf 'expected output to contain: %s\nactual output:\n%s\n' "$expected" "$output" >&2
        exit 1
    fi
}

test_valid_environment() {
    output="$(run_check)"

    assert_contains "$output" 'Android SDK: ok (API 37.0)'
    assert_contains "$output" 'Android NDK: ok (27.3.13750724)'
    assert_contains "$output" 'Java: ok (21)'
    assert_contains "$output" 'Rust: ok (1.96.0)'
    assert_contains "$output" 'Rust targets: ok (aarch64-linux-android, x86_64-linux-android)'
}

test_missing_platform_fails() {
    rmdir "$TEST_ROOT/sdk/platforms/android-37.0"

    if output="$(run_check)"; then
        printf 'expected a missing Android platform to fail\n' >&2
        exit 1
    fi

    assert_contains "$output" 'missing Android SDK platform: android-37.0'
}

prepare_fixture
test_valid_environment
test_missing_platform_fails
printf '%s\n' 'android environment checks passed'
