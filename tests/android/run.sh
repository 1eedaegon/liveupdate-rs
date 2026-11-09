#!/bin/sh
# The device suite: the instrumentation tests on an emulator, with Kotlin and Rust coverage.
#
#   ANDROID_SERIAL=emulator-5580 [EXPECTED_SDK=37] [OUT=dir] tests/android/run.sh
#
# 1. Refuses any device that is not an emulator: every adb call names ANDROID_SERIAL.
# 2. Builds the test APK, whose Rust library (android/native-test) is built with -C instrument-coverage
#    for the emulator's ABI, and runs every instrumentation test with JaCoCo coverage. EXPECTED_SDK makes
#    the tests fail on any other API level.
# 3. Pulls JaCoCo's execution data and the Rust profile, then checks the Kotlin line coverage of unit
#    and instrumentation tests together (android/build.gradle.kts) and the Rust line coverage of
#    src/android.rs on the device (at least RUST_ANDROID_MIN percent, 90 by default).
# 4. Writes $OUT/passed.txt only when everything passed. OUT is build/device-suite by default.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
out=${OUT:-$root/android/build/device-suite}
minimum=${RUST_ANDROID_MIN:-90}
package=io.github.eedaegon.liveupdate.test
runner=$package/androidx.test.runner.AndroidJUnitRunner
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:?ANDROID_HOME or ANDROID_SDK_ROOT names the Android SDK}}
adb=$sdk/platform-tools/adb

serial=${ANDROID_SERIAL:?ANDROID_SERIAL names the emulator to test on}
case $serial in
  emulator-[0-9]*) ;;
  *) echo "refusing $serial: the device suite runs on emulators only" >&2; exit 2 ;;
esac
device() { "$adb" -s "$serial" "$@"; }
qemu=$(device shell 'getprop ro.boot.qemu; getprop ro.kernel.qemu' | tr -d '\r' | grep -c '^1$' || true)
[ "$qemu" -gt 0 ] || { echo "refusing $serial: it does not report itself as an emulator" >&2; exit 2; }

rm -rf "$out" "$root/android/build/outputs/code_coverage/device"
mkdir -p "$out" "$root/android/build/outputs/code_coverage/device"
api=$(device shell getprop ro.build.version.sdk | tr -d '\r')
abi=$(device shell getprop ro.product.cpu.abi | tr -d '\r')
{
  echo "serial $serial"
  echo "sdk $api"
  echo "model $(device shell getprop ro.product.model | tr -d '\r')"
  echo "fingerprint $(device shell getprop ro.build.fingerprint | tr -d '\r')"
  echo "abi $abi"
} | tee "$out/device.txt"
if [ -n "${EXPECTED_SDK:-}" ] && [ "$api" != "$EXPECTED_SDK" ]; then
  echo "the emulator runs API $api, not $EXPECTED_SDK" >&2
  exit 1
fi
case $abi in
  x86_64) triple=x86_64-linux-android ;;
  arm64-v8a) triple=aarch64-linux-android ;;
  *) echo "unsupported emulator ABI $abi" >&2; exit 1 ;;
esac

cd "$root"
gradle :android:testDebugUnitTest :android:assembleDebugAndroidTest --no-daemon \
  -Pliveupdate.nativeAbis="$abi" -Pliveupdate.nativeCoverage=true
apk=android/build/outputs/apk/androidTest/debug/android-debug-androidTest.apk
library=android/build/native-test-target/$triple/debug/libliveupdate_android_native_test.so
[ -f "$apk" ] && [ -f "$library" ] || { echo "the build left no $apk or $library" >&2; exit 1; }

# One instrumentation run: NAME, then the runner's arguments. Its JaCoCo data lands in
# android/build/outputs/code_coverage/device/NAME.ec; it prints the number of tests that passed.
instrument() {
  name=$1
  shift
  status=0
  device shell am instrument -w -e coverage true -e coverageFile "/data/data/$package/$name.ec" "$@" "$runner" \
    | tr -d '\r' >"$out/instrumentation-$name.txt" || status=$?
  cat "$out/instrumentation-$name.txt" >&2
  count=$(sed -n 's/^OK (\([0-9]*\) tests\{0,1\})$/\1/p' "$out/instrumentation-$name.txt")
  if [ "$status" != 0 ] || [ -z "$count" ] || grep -q '^FAILURES!!!' "$out/instrumentation-$name.txt"; then
    echo "the $name instrumentation run failed" >&2
    device uninstall "$package" >/dev/null 2>&1 || true
    exit 1
  fi
  device exec-out run-as "$package" cat "/data/data/$package/$name.ec" \
    >"android/build/outputs/code_coverage/device/$name.ec"
  [ -s "android/build/outputs/code_coverage/device/$name.ec" ] || { echo "no JaCoCo data of $name" >&2; exit 1; }
  echo "$count"
}

device uninstall "$package" >/dev/null 2>&1 || true
device install -r -t -g "$apk"
passed=$(instrument main ${EXPECTED_SDK:+-e expectedSdk "$EXPECTED_SDK"})
device exec-out run-as "$package" cat files/liveupdate.profraw >"$out/liveupdate.profraw"
device logcat -d -s TestRunner:I System.out:I >"$out/logcat.txt" 2>/dev/null || true
# Revoking the notification permission kills the app, so the class that needs it revoked runs alone.
device shell pm revoke "$package" android.permission.POST_NOTIFICATIONS
denied=$(instrument denied -e class io.github.eedaegon.liveupdate.PermissionInstrumentationTest -e notifications denied)
device uninstall "$package" >/dev/null
[ -s "$out/liveupdate.profraw" ] || { echo "no Rust profile on $serial" >&2; exit 1; }

gradle :android:coverageVerification --no-daemon
kotlin=$(python3 -I - android/build/reports/jacoco/coverageReport/coverageReport.xml <<'PYTHON'
import sys, xml.etree.ElementTree as tree
report = tree.parse(sys.argv[1]).getroot()
line = next(c for c in report.findall("counter") if c.get("type") == "LINE")
missed, covered = int(line.get("missed")), int(line.get("covered"))
print(f"{100 * covered / (missed + covered):.2f}% ({covered}/{missed + covered} lines)")
PYTHON
)
echo "Kotlin line coverage, unit and instrumentation tests: $kotlin"

host=$(rustc -vV | sed -n 's/^host: //p')
tools=$(rustc --print sysroot)/lib/rustlib/$host/bin
"$tools/llvm-profdata" merge -sparse "$out/liveupdate.profraw" -o "$out/liveupdate.profdata"
"$tools/llvm-cov" report --instr-profile="$out/liveupdate.profdata" "$library" "$root/src" \
  | tee "$out/rust-coverage.txt"
"$tools/llvm-cov" export --summary-only --instr-profile="$out/liveupdate.profdata" "$library" "$root/src" \
  >"$out/rust-coverage.json"
android=$(python3 -I - "$out/rust-coverage.json" "$minimum" <<'PYTHON'
import json, sys
report, minimum = json.load(open(sys.argv[1])), float(sys.argv[2])
files = {f["filename"].rsplit("/", 1)[-1]: f["summary"]["lines"] for f in report["data"][0]["files"]}
lines = files.get("android.rs")
if lines is None:
    sys.exit("src/android.rs has no coverage data")
print(f"{lines['percent']:.2f}% ({lines['covered']}/{lines['count']} lines)")
if lines["percent"] < minimum:
    sys.exit(f"src/android.rs line coverage {lines['percent']:.2f}% is below {minimum}%")
PYTHON
)
echo "Rust line coverage of src/android.rs on the device: $android"

{
  cat "$out/device.txt"
  echo "tests $passed and $denied without the notification permission"
  echo "kotlin $kotlin"
  echo "android.rs $android"
} >"$out/passed.txt"
echo "device suite passed: $passed tests, and $denied without the notification permission"
