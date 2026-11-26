#!/bin/sh
# What the release AAR holds. Run after `gradle :android:assembleRelease`.
#
#   tests/android/aar.sh [AAR]    AAR defaults to android/build/outputs/aar/android-release.aar
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
aar=${1:-$root/android/build/outputs/aar/android-release.aar}
[ -f "$aar" ] || { echo "no AAR at $aar: run gradle :android:assembleRelease first" >&2; exit 1; }
work=$(mktemp -d "${TMPDIR:-/tmp}/liveupdate-aar.XXXXXX")
trap 'rm -rf "$work"' EXIT HUP INT TERM
unzip -q "$aar" -d "$work"
failures=0
fail() { echo "FAIL $1"; failures=$((failures + 1)); }

# LU-AAR-1, LU-SCHED-13
manifest_declares_both_notification_permissions() {
  for permission in POST_NOTIFICATIONS POST_PROMOTED_NOTIFICATIONS; do
    grep -q "\"android.permission.$permission\"" "$work/AndroidManifest.xml" \
      || fail "the manifest does not declare $permission"
  done
  if grep -qE '<(activity|service|provider)' "$work/AndroidManifest.xml"; then
    fail "the manifest declares an unexpected component"
  fi
  permission_count=$(grep -c '<uses-permission' "$work/AndroidManifest.xml" || true)
  [ "$permission_count" = 2 ] || fail "the manifest must declare exactly two permissions"
  receiver_count=$(grep -c '<receiver' "$work/AndroidManifest.xml" || true)
  [ "$receiver_count" = 3 ] || fail "the manifest must declare exactly three receivers"
  for receiver in LiveUpdateActionReceiver LiveUpdateDismissReceiver LiveUpdateStartReceiver; do
    grep -A2 "android:name=\"io.github.eedaegon.liveupdate.$receiver\"" \
      "$work/AndroidManifest.xml" | grep -q 'android:exported="false"' \
      || fail "$receiver is missing or exported"
  done
  # A restart of the device is the host's to declare.
  if grep -q '<intent-filter' "$work/AndroidManifest.xml"; then
    fail "the manifest declares an intent filter"
  fi
}

# LU-AAR-2
consumer_rules_keep_the_jni_entry_point() {
  grep -qF -- '-keep class io.github.eedaegon.liveupdate.LiveUpdateBridge { public static *; }' \
    "$work/proguard.txt" || fail "proguard.txt does not keep LiveUpdateBridge"
}

# LU-AAR-3
the_aar_holds_the_bridge_and_its_resources() {
  classes=$(unzip -Z1 "$work/classes.jar")
  for class in LiveUpdateBridge AndroidAdapter LiveUpdateProtocol AdapterFailure \
    LiveUpdateActionReceiver LiveUpdateDismissReceiver LiveUpdateStartReceiver ScheduledStarts \
    NotificationRenderer EventStore IconResolver; do
    printf '%s\n' "$classes" | grep -qx "io/github/eedaegon/liveupdate/$class.class" \
      || fail "classes.jar lacks $class"
  done
  [ -f "$work/res/drawable/liveupdate_status.xml" ] || fail "the drawable liveupdate_status is missing"
  # The channels' names, which an application overrides with its own strings.
  for name in liveupdate_channel_name liveupdate_channel_description liveupdate_alert_channel_name \
    liveupdate_alert_channel_description liveupdate_watch_channel_name liveupdate_watch_channel_description; do
    grep -q "name=\"$name\"" "$work/res/values/values.xml" || fail "the string $name is missing"
  done
  grep -q 'package="io.github.eedaegon.liveupdate"' "$work/AndroidManifest.xml" || fail "the namespace differs"
  grep -q 'android:minSdkVersion="26"' "$work/AndroidManifest.xml" || fail "minSdk is not 26"
}

# LU-WEAR-5
only_the_wear_class_names_androidx() {
  mkdir "$work/classes"
  unzip -q "$work/classes.jar" -d "$work/classes"
  # A class names another by its path, with slashes.
  naming=$(cd "$work/classes" && grep -rl 'androidx/' . | sort | tr '\n' ' ')
  [ "$naming" = "./io/github/eedaegon/liveupdate/WearOngoing.class " ] \
    || fail "the classes that name AndroidX are: ${naming:-none}"
  for rule in '-dontwarn androidx.wear.ongoing.**' '-dontwarn androidx.core.app.NotificationCompat$Builder' \
    '-keep class io.github.eedaegon.liveupdate.WearOngoing { *; }'; do
    grep -qF -- "$rule" "$work/proguard.txt" || fail "proguard.txt lacks $rule"
  done
}

manifest_declares_both_notification_permissions
consumer_rules_keep_the_jni_entry_point
the_aar_holds_the_bridge_and_its_resources
only_the_wear_class_names_androidx
[ "$failures" = 0 ] || { echo "$failures AAR check(s) failed"; exit 1; }
echo "aar: permissions, keep rules, classes and resources as specified"
