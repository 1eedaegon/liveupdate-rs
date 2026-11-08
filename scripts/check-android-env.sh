#!/bin/sh

set -eu

readonly ANDROID_API_VERSION="${LIVEUPDATE_ANDROID_API_VERSION:-37.0}"
readonly ANDROID_NDK_VERSION="${LIVEUPDATE_ANDROID_NDK_VERSION:-27.3.13750724}"
readonly RUST_VERSION="${LIVEUPDATE_RUST_VERSION:-1.96.0}"
readonly RUST_TARGETS='aarch64-linux-android x86_64-linux-android'

fail() {
    printf 'error: %s\n' "$1" >&2
    exit 1
}

require_command() {
    command_name="$1"
    command -v "$command_name" >/dev/null 2>&1 \
        || fail "missing command: $command_name (run this inside 'nix develop')"
}

find_android_sdk() {
    if [ -n "${ANDROID_HOME:-}" ]; then
        printf '%s\n' "$ANDROID_HOME"
        return
    fi
    if [ -n "${ANDROID_SDK_ROOT:-}" ]; then
        printf '%s\n' "$ANDROID_SDK_ROOT"
        return
    fi
    if [ -d "$HOME/Library/Android/sdk" ]; then
        printf '%s\n' "$HOME/Library/Android/sdk"
        return
    fi
    if [ -d "$HOME/Android/Sdk" ]; then
        printf '%s\n' "$HOME/Android/Sdk"
        return
    fi
    fail 'Android SDK not found; set ANDROID_HOME'
}

find_java() {
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        printf '%s\n' "$JAVA_HOME/bin/java"
        return
    fi
    command -v java || fail "missing command: java (run this inside 'nix develop')"
}

java_major_version() {
    java_bin="$1"
    version_line="$("$java_bin" -version 2>&1 | sed -n '1p')"
    major="$(printf '%s\n' "$version_line" | sed -E 's/.*version "([0-9]+).*/\1/')"
    [ -n "$major" ] && [ "$major" != "$version_line" ] \
        || fail "cannot parse Java version: $version_line"
    printf '%s\n' "$major"
}

android_ndk_root() {
    sdk_root="$1"
    printf '%s\n' "${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-$sdk_root/ndk/$ANDROID_NDK_VERSION}}"
}

validate_rust_targets() {
    rust_sysroot="$1"
    for target in $RUST_TARGETS; do
        [ -d "$rust_sysroot/lib/rustlib/$target/lib" ] \
            || fail "missing Rust target: $target"
    done
}

main() {
    require_command cargo
    require_command cargo-ndk
    require_command rustc

    sdk_root="$(find_android_sdk)"
    platform_name="android-$ANDROID_API_VERSION"
    [ -d "$sdk_root/platforms/$platform_name" ] \
        || fail "missing Android SDK platform: $platform_name"

    ndk_root="$(android_ndk_root "$sdk_root")"
    [ -f "$ndk_root/source.properties" ] \
        || fail "missing Android NDK: $ANDROID_NDK_VERSION"
    grep -F "Pkg.Revision = $ANDROID_NDK_VERSION" "$ndk_root/source.properties" >/dev/null \
        || fail "Android NDK must be $ANDROID_NDK_VERSION"

    java_major="$(java_major_version "$(find_java)")"
    [ "$java_major" = '21' ] || fail "Java 21 required; found $java_major"

    rust_version="$(rustc --version)"
    case "$rust_version" in
        "rustc $RUST_VERSION "*) ;;
        *) fail "Rust $RUST_VERSION required; found $rust_version" ;;
    esac
    rust_sysroot="$(rustc --print sysroot)"
    validate_rust_targets "$rust_sysroot"

    printf 'Android SDK: ok (API %s)\n' "$ANDROID_API_VERSION"
    printf 'Android NDK: ok (%s)\n' "$ANDROID_NDK_VERSION"
    printf 'Java: ok (%s)\n' "$java_major"
    printf 'Rust: ok (%s)\n' "$RUST_VERSION"
    printf 'Rust targets: ok (%s)\n' "$(printf '%s' "$RUST_TARGETS" | sed 's/ /, /g')"
}

main "$@"
