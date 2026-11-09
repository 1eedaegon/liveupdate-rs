#!/bin/sh
# An Android emulator for the device suite, on an exact system image.
#
#   avd.sh create NAME [API]   Writes the AVD NAME for system-images/android-API/google_apis/ABI (API 37.0 by
#                              default, ABI the host's), with a Pixel 9 Pro XL screen. It writes the AVD's two files
#                              itself: `android emulator create` takes no system image, and picked API 36.
#   avd.sh start NAME [PORT]   Boots NAME headless on PORT (5580 by default), waits until Android has booted, and
#                              prints serial=emulator-PORT.
#   avd.sh stop SERIAL         Stops the emulator SERIAL, which must be an emulator.
#   avd.sh delete NAME         Deletes the AVD NAME.
#
# AVDs live in $ANDROID_AVD_HOME (default ~/.android/avd); the SDK is $ANDROID_SDK_ROOT or $ANDROID_HOME.
set -eu
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:?ANDROID_HOME or ANDROID_SDK_ROOT names the Android SDK}}
avd_home=${ANDROID_AVD_HOME:-$HOME/.android/avd}
adb=$sdk/platform-tools/adb

host_abi() {
  case $(uname -m) in
    arm64 | aarch64) echo arm64-v8a ;;
    *) echo x86_64 ;;
  esac
}

create() {
  name=$1 api=${2:-37.0}
  abi=$(host_abi)
  image=system-images/android-$api/google_apis/$abi
  [ -f "$sdk/$image/system.img" ] || { echo "missing $image in $sdk" >&2; exit 1; }
  mkdir -p "$avd_home/$name.avd"
  cat >"$avd_home/$name.ini" <<INI
avd.ini.encoding=UTF-8
path=$avd_home/$name.avd
path.rel=avd/$name.avd
target=android-$api
INI
  cat >"$avd_home/$name.avd/config.ini" <<INI
AvdId=$name
avd.ini.displayname=$name
abi.type=$abi
hw.cpu.arch=$(case $abi in arm64-v8a) echo arm64 ;; *) echo x86_64 ;; esac)
image.sysdir.1=$image/
tag.id=google_apis
tag.display=Google APIs
PlayStore.enabled=no
hw.device.name=pixel_9_pro_xl
hw.device.manufacturer=Google
hw.lcd.width=1344
hw.lcd.height=2992
hw.lcd.density=480
hw.ramSize=2048
hw.keyboard=yes
hw.gpu.enabled=yes
disk.dataPartition.size=4G
fastboot.forceColdBoot=yes
INI
  echo "created $name: $image"
}

start() {
  name=$1 port=${2:-5580}
  serial=emulator-$port
  [ -f "$avd_home/$name.ini" ] || { echo "no AVD $name in $avd_home" >&2; exit 1; }
  if "$adb" devices | grep -q "^$serial[[:space:]]"; then echo "$serial is already running" >&2; exit 1; fi
  log=${TMPDIR:-/tmp}/emulator-$name.log
  ANDROID_AVD_HOME=$avd_home nohup "$sdk/emulator/emulator" -avd "$name" -port "$port" -no-window -no-audio \
    -no-boot-anim -no-snapshot -gpu swiftshader_indirect -camera-back none -camera-front none >"$log" 2>&1 &
  echo "emulator $name on $serial (log $log)" >&2
  booted=
  for _ in $(seq 1 180); do
    if [ "$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then
      booted=1
      break
    fi
    sleep 2
  done
  [ -n "$booted" ] || { tail -40 "$log" >&2; echo "$serial did not boot" >&2; exit 1; }
  # The package manager answers once the boot is really over.
  for _ in $(seq 1 60); do "$adb" -s "$serial" shell pm path android >/dev/null 2>&1 && break; sleep 2; done
  for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
    "$adb" -s "$serial" shell settings put global "$setting" 0
  done
  echo "serial=$serial"
}

stop() {
  serial=$1
  case $serial in emulator-[0-9]*) ;; *) echo "not an emulator: $serial" >&2; exit 2 ;; esac
  "$adb" -s "$serial" emu kill >/dev/null 2>&1 || true
  for _ in $(seq 1 30); do
    "$adb" devices | grep -q "^$serial[[:space:]]" || { echo "stopped $serial"; return; }
    sleep 1
  done
  echo "$serial is still listed" >&2
}

delete() {
  name=$1
  [ -n "$name" ] || exit 2
  rm -rf "${avd_home:?}/$name.avd" "${avd_home:?}/$name.ini"
  echo "deleted $name"
}

command=${1:-}
[ $# -eq 0 ] || shift
case $command in
  create) create "$@" ;;
  start) start "$@" ;;
  stop) stop "$@" ;;
  delete) delete "$@" ;;
  *) echo "usage: avd.sh create NAME [API] | start NAME [PORT] | stop SERIAL | delete NAME" >&2; exit 2 ;;
esac
