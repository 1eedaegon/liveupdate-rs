#!/bin/sh
# An application built as a host builds one, on one device: the crate and the AAR as they are published
# (LIVEUPDATE_VERSION) or as this checkout builds them, the README's installation word for word, R8 and
# resource shrinking, and an icon that only Rust names (tests/android/consumer).
#
#   ANDROID_SERIAL=SERIAL [LIVEUPDATE_VERSION=0.7.1] [WEAR=1] [OUT=dir] tests/android/consumer.sh COMMAND
#
#   build              Fetches the published AAR and checks its sha256, or builds this checkout's; builds the
#                      Rust library for the device's ABI with cargo-ndk, then the release APK. WEAR=1 adds
#                      androidx.wear:wear-ongoing, as a watch application does. From 0.8.0, and from this checkout,
#                      the application has a recorder: a foreground service that an action's target reaches.
#   run                Installs it and checks, failing at the first that does not hold: the capabilities answer;
#                      a start shows its notification; an update shows its revision; a scheduled start is posted
#                      by its alarm after the process was ended; an end removes both. A watch build on a watch
#                      must offer the Ongoing Activity: a start carries one, and an update and an end at once
#                      leave the last content without it. A build with the recorder (0.8.0 and later, or this
#                      checkout) on a device that is not a watch: its activity's Record button, tapped in the
#                      shade, reaches the recorder service, which takes it as handled and updates the activity
#                      through its own client; at the end the service keeps its own notification.
#                      From 0.10.0, and from this checkout: a start, an update and an end by data message, each in a
#                      process that the message started, are shown, and the application drains a `pushed` event of each.
#   scenario NAME [JSON]  Runs one scenario of the application's Rust code and prints its answer.
#   push MEMBER        Hands a data message's member to LiveUpdatePush through the application's receiver, as a
#                      messaging service does, and prints the receiver's answer (0.10.0 and later, or this checkout).
#   tap TITLE          Opens the notification shade and taps the button titled TITLE (any case), then prints what
#                      the application logged within five seconds: the recorder's taps, events, openings. On a
#                      phone or an emulator; a watch does not list an ongoing notification.
#   uninstall          Removes the application, and with it what `run` changed.
#
# What `run` does to the device: it uninstalls an earlier copy, installs with every runtime permission granted,
# allows the exact alarm (API 31 and later), and ends the application's process with `am kill`. Android 8.x
# (API 26 and 27) keeps that cached process; there it ends every background process with `am kill-all`, and
# elsewhere only with KILL_ALL=1. The application stays installed for what is looked at by hand, and its
# receiver, exported for adb, runs a scenario for any caller: uninstall it afterwards. Every adb call names
# ANDROID_SERIAL. A phone is a person's: run on one only with its owner's word. OUT is build/consumer by default;
# the checkout's sources are never changed.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
out=${OUT:-$root/build/consumer}
mkdir -p "$out"
out=$(CDPATH= cd -- "$out" && pwd)
package=io.github.eedaegon.liveupdate.consumer
# LiveUpdateForeground.NOTIFICATION_ID, and the recorder's FALLBACK_ID.
ACTIVITY_ID=1280638977
FALLBACK_ID=786433
tag=LiveUpdateConsumer
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:?ANDROID_HOME or ANDROID_SDK_ROOT names the Android SDK}}
adb=$sdk/platform-tools/adb
serial=${ANDROID_SERIAL:?ANDROID_SERIAL names the device}
apk=$out/app/app/build/outputs/apk/release/app-release.apk
device() { "$adb" -s "$serial" "$@"; }
fail() { echo "consumer: $*" >&2; exit 1; }

# A property of the device; an empty answer is adb's failure.
property() {
  value=$(device shell getprop "$1" | tr -d '\r')
  [ -n "$value" ] || fail "the device answered no $1"
  echo "$value"
}

build() {
  rm -rf "$out/app" "$out/rust" "$out/jniLibs"
  cp -R "$root/tests/android/consumer/." "$out/app"
  mv "$out/app/rust" "$out/rust"
  if [ -n "${LIVEUPDATE_VERSION:-}" ]; then
    version=$LIVEUPDATE_VERSION
    dependency="liveupdate = { version = \"=$version\", features = [\"android\"] }"
    aar=$out/liveupdate-android-$version.aar
    base=https://github.com/1eedaegon/liveupdate-rs/releases/download/v$version
    curl -fsSL --retry 3 --max-time 300 -o "$aar" "$base/liveupdate-android-$version.aar"
    curl -fsSL --retry 3 --max-time 60 -o "$aar.sha256" "$base/liveupdate-android-$version.aar.sha256"
    # The sum comes from the same release: it finds a broken download, not a forged one.
    (cd "$out" && shasum -a 256 -c "$(basename "$aar").sha256") || fail "the AAR does not match its sha256"
  else
    dependency="liveupdate = { path = \"$root\", features = [\"android\"] }"
    (cd "$root" && gradle :android:assembleRelease --no-daemon -q)
    aar=$out/liveupdate-android-checkout.aar
    cp "$root/android/build/outputs/aar/android-release.aar" "$aar"
  fi
  python3 -I - "$out/rust/Cargo.toml" "$dependency" <<'PYTHON'
import sys
path, dependency = sys.argv[1], sys.argv[2]
lines = open(path).read().split("\n")
hits = [n for n, line in enumerate(lines) if line.endswith("# LIVEUPDATE")]
assert len(hits) == 1, "no dependency line to replace"
lines[hits[0]] = dependency
open(path, "w").write("\n".join(lines))
PYTHON
  device_abi=$(property ro.product.cpu.abi)
  wear=false
  [ "${WEAR:-}" = 1 ] && wear=true
  # Action targets came with 0.8.0, and channels named by the application's strings with 0.9.0.
  since() { python3 -I -c 'import sys; v, at = sys.argv[1], tuple(map(int, sys.argv[2].split("."))); print("true" if not v or tuple(map(int, v.split("-")[0].split("."))) >= at else "false")' "${LIVEUPDATE_VERSION:-}" "$1"; }
  targets=$(since 0.8.0)
  named=$(since 0.9.0)
  pushed=$(since 0.10.0)
  echo "$named" >"$out/named"
  features=
  [ "$named" = true ] && features="--features platform"
  if [ "$pushed" = true ]; then
    features="${features:---features }${features:+,}push"
    python3 -I - "$out/app/app/src/main/AndroidManifest.xml" <<'PYTHON'
import sys
path = sys.argv[1]
text = open(path).read()
marker = "        <!-- PUSH: a version that has activities from a push puts its receiver and the library's name here. -->\n"
assert text.count(marker) == 1, "no place for the push's receiver"
receiver = """        <meta-data
            android:name="io.github.eedaegon.liveupdate.LIBRARY"
            android:value="liveupdate_consumer" />
        <receiver
            android:name=".PushReceiver"
            android:exported="true" />
"""
open(path, "w").write(text.replace(marker, receiver, 1))
PYTHON
  fi
  if [ "$targets" = true ]; then
    features="${features:---features }${features:+,}targets"
    python3 -I - "$out/app/app/src/main/AndroidManifest.xml" <<'PYTHON'
import sys
path = sys.argv[1]
text = open(path).read()
marker = "        <!-- TARGETS: a version that has action targets puts its recorder service here. -->\n"
assert text.count(marker) == 1, "no place for the recorder"
service = """        <service
            android:name=".RecorderService"
            android:exported="false"
            android:foregroundServiceType="shortService" />
"""
open(path, "w").write(text.replace(marker, service, 1))
PYTHON
  fi
  # shellcheck disable=SC2086
  CARGO_TARGET_DIR=$out/rust-target cargo ndk -t "$device_abi" -o "$out/jniLibs" \
    build --release --manifest-path "$out/rust/Cargo.toml" $features
  gradle -p "$out/app" :app:assembleRelease --no-daemon -q \
    -Pconsumer.aar="$aar" -Pconsumer.jniLibs="$out/jniLibs" -Pconsumer.wear="$wear" -Pconsumer.targets="$targets" \
    -Pconsumer.push="$pushed"
  [ -f "$apk" ] || fail "the build left no $apk"
  echo "$wear" >"$out/wear"
  echo "$targets" >"$out/targets"
  echo "$pushed" >"$out/push"
  echo "built $apk for $device_abi with $(sed -n 's/^liveupdate = //p' "$out/rust/Cargo.toml")"
}

# One scenario through the application's receiver; prints the JSON it logged. A nonce tells this answer from
# every earlier one, so the device's log is never cleared.
scenario() {
  name=$1 input=${2:-"{}"}
  case $name in *[!a-z-]*|'') fail "not a scenario: $name" ;; esac
  case $input in *"'"*) fail "the input may not have a single quote" ;; esac
  nonce=$(od -An -N6 -tu2 /dev/urandom | tr -d ' \n')
  device shell am broadcast -n "$package/.ScenarioReceiver" --es scenario "$name" --es nonce "$nonce" \
    --es input "'$input'" >/dev/null
  for _ in $(seq 1 50); do
    line=$(device logcat -d -v tag -s "$tag:I" | tr -d '\r' | sed -n "s/^I\/$tag: $name#$nonce //p" | tail -1)
    [ -n "$line" ] && { echo "$line"; return 0; }
    sleep 0.4
  done
  fail "no answer to $name"
}

# A field of an answer, by a Python expression on `a`, the decoded answer.
field() { python3 -I -c "import json,sys; a=json.loads(sys.argv[1]); print($2)" "$1"; }

# The active notifications, read once for a poll. A failed or empty dump fails: a check that something is gone
# must not pass because adb did not answer.
dump() {
  text=$(device shell dumpsys notification --noredact) || fail "dumpsys notification failed"
  case $text in *"Notification List:"*) ;; *) fail "dumpsys notification answered no list" ;; esac
  printf '%s\n' "$text" | tr -d '\r'
}

# The record of the activity [2] in the dump [1], up to the next record: empty when it is not shown.
record() {
  printf '%s\n' "$1" | grep -A 80 "NotificationRecord(.* tag=liveupdate-rs:$2 " | sed '/^ *NotificationRecord(/{1!q;}' || true
}

shown() { [ -n "$(record "$1" "$2")" ]; }
carries() { record "$1" "$2" | grep -q "android.wearable.ongoingactivities.EXTENSIONS"; }

# The application's process, as `pidof` answers it; "none" when there is none, empty when adb failed.
process() { device shell "pidof $package || echo none" | tr -d '\r'; }

wait_for() {
  what=$1 condition=$2 seconds=${3:-60}
  waited=0
  while [ "$waited" -lt "$seconds" ]; do
    eval "$condition" && return 0
    sleep 2
    waited=$((waited + 2))
  done
  fail "timed out waiting until $what"
}

run() {
  [ -f "$apk" ] || fail "no $apk: build first"
  device uninstall "$package" >/dev/null 2>&1 || true
  device install -r -g "$apk" >/dev/null
  api=$(property ro.build.version.sdk)
  if [ "$api" -ge 31 ]; then device shell cmd appops set "$package" SCHEDULE_EXACT_ALARM allow; fi
  watch=$(device shell pm list features | tr -d '\r' | grep -c "feature:android.hardware.type.watch$" || true)
  echo "device $(property ro.product.model), API $api, $(property ro.product.cpu.abi)$([ "$watch" = 1 ] && echo ", a watch")"

  capabilities=$(scenario capabilities)
  read -r answered promotion offered <<EOF
$(field "$capabilities" "'ok' in a, a.get('ok', {}).get('promotionEnabled'), a.get('ok', {}).get('presentation', {}).get('wearOngoingActivity')")
EOF
  [ "$answered" = True ] || fail "capabilities: $capabilities"
  echo "capabilities: promotion $promotion, the watch's Ongoing Activity $offered"
  if [ "$(cat "$out/wear" 2>/dev/null)" = true ] && [ "$watch" = 1 ] && [ "$offered" != True ]; then
    fail "a watch application with the library, on a watch, is offered no Ongoing Activity"
  fi

  started=$(scenario start)
  [ "$(field "$started" "'ok' in a")" = True ] || fail "start: $started"
  ride=$(field "$started" "a['ok']['id']")
  wait_for "the start is shown" 'shown "$(dump)" "$ride"'
  echo "start: shown, $(field "$started" "a['ok']['promotion']")"
  if [ "$(cat "$out/named" 2>/dev/null)" = true ]; then
    # The application's own strings name the library's channel.
    dump | grep -c "mId='liveupdate-rs.v1', mName=Consumer rides," >/dev/null ||
      fail "the channel is not named by the application's strings"
    echo "channel: named by the application, Consumer rides"
  fi

  updated=$(scenario update)
  [ "$(field "$updated" "a['ok']['revision']")" = 1 ] || fail "update: $updated"
  wait_for "revision 1 is shown" 'record "$(dump)" "$ride" | grep -q "\"revision\":1"'
  echo "update: revision 1 shown"

  scheduled=$(scenario schedule '{"delayMs":20000}')
  [ "$(field "$scheduled" "a['ok']['state']")" = scheduled ] || fail "schedule: $scheduled"
  later=$(field "$scheduled" "a['ok']['id']")
  # The application's process ends, as Android ends one in the background: the alarm starts it again.
  end_process
  # The process was gone before: whatever posts it is a process that the alarm started, which may end again at once.
  wait_for "the scheduled start is shown" 'shown "$(dump)" "$later"' 180
  echo "scheduled start: posted by its alarm, in a process that the alarm started"

  ended=$(scenario end)
  [ "$(field "$ended" "len(a['ok'])")" = 2 ] || fail "end: $ended"
  wait_for "both are gone" 'now=$(dump) && ! shown "$now" "$ride" && ! shown "$now" "$later"'
  echo "end: both removed"

  recorder_check
  push_check

  if [ "$offered" = True ]; then
    again=$(scenario start)
    ride=$(field "$again" "a['ok']['id']")
    wait_for "the start carries the Ongoing Activity" 'carries "$(dump)" "$ride"'
    # The lifecycle's own calls one after the other, faster than Android lists a post.
    finished=$(scenario quick-end)
    [ "$(field "$finished" "a['ok'][1]['state']")" = ended ] || fail "quick-end: $finished"
    wait_for "the last content is shown without the Ongoing Activity" \
      'now=$(dump) && shown "$now" "$ride" && ! carries "$now" "$ride"'
    echo "watch: a start carries the Ongoing Activity, and an update and an end at once leave none"
  else
    echo "watch: not checked, the device offers no Ongoing Activity"
  fi
  echo "consumer run passed on $serial"
}

# The notification of the foreground placement, without a tag, under LiveUpdateForeground.NOTIFICATION_ID; and
# the recorder's own, under its FALLBACK_ID. Each answers whether it is shown and held by the service.
# Ends the application's process, as Android ends one in the background.
end_process() {
  # `am kill` ends only a process that Android counts as in the background: one that a receiver just ran in
  # gets there within seconds, so it is asked again until then.
  gone='device shell am kill "$package" && sleep 1 && [ "$(process)" = none ]'
  if ! (wait_for "the process is gone" "$gone" 30) 2>/dev/null; then
    [ "$api" -le 27 ] || [ "${KILL_ALL:-}" = 1 ] || fail "am kill left the process; KILL_ALL=1 ends every background process"
    device shell am kill-all
    wait_for "the process is gone" "$gone" 20
  fi
}

# A message's member, handed to LiveUpdatePush by the application's receiver as a messaging service does; prints
# the receiver's answer.
push() {
  member=$1
  case $member in *"'"*) fail "the member may not have a single quote" ;; esac
  nonce=$(od -An -N6 -tu2 /dev/urandom | tr -d ' \n')
  device shell am broadcast -n "$package/.PushReceiver" --es member "'$member'" --es nonce "$nonce" >/dev/null
  for _ in $(seq 1 50); do
    line=$(device logcat -d -v tag -s "$tag:I" | tr -d '\r' | sed -n "s/^I\/$tag: push#$nonce //p" | tail -1)
    [ -n "$line" ] && { echo "$line"; return 0; }
    sleep 0.4
  done
  fail "no answer to the push"
}

# The `pushed` events that the application logged since $since, a time on the device's clock.
pushed_events() {
  device logcat -d -v tag -T "'$since'" -s "$tag:I" | grep -o '"kind":"pushed"' | wc -l | tr -d ' '
}

# A start, an update and an end by data message, each in a process that the message started, and what the
# application drained of them: one `pushed` event each.
push_check() {
  [ "$(cat "$out/push" 2>/dev/null)" = true ] || { echo "push: not in this build"; return 0; }
  member() {
    answer=$(scenario payload "$1")
    [ "$(field "$answer" "'ok' in a")" = True ] || fail "payload: $answer"
    field "$answer" "a['ok']"
  }
  start=$(member '{"command":"start"}')
  update=$(member '{"command":"update","revision":1}')
  finish=$(member '{"command":"end"}')
  since=$(device shell date "+%m-%d_%H:%M:%S.000" | tr -d '\r' | tr _ ' ')
  end_process
  [ "$(field "$(push "$start")" "a['handled']")" = True ] || fail "push: the start was not the library's"
  pushed=$(scenario list)
  ride=$(field "$pushed" "[r['id'] for r in a['ok'] if r['activityKey'] == 'consumer:ride' and r['state'] not in ('ended', 'dismissed')][0]")
  wait_for "the pushed start is shown" 'shown "$(dump)" "$ride"'
  end_process
  [ "$(field "$(push "$update")" "a['handled']")" = True ] || fail "push: the update was not the library's"
  wait_for "the pushed revision 1 is shown" 'record "$(dump)" "$ride" | grep -q "\"revision\":1"'
  end_process
  [ "$(field "$(push "$finish")" "a['handled']")" = True ] || fail "push: the end was not the library's"
  wait_for "the pushed end removed it" '! shown "$(dump)" "$ride"'
  wait_for "the application drained a pushed event of each" '[ "$(pushed_events)" -ge 3 ]' 20
  echo "push: a start, an update and an end by data message, each in a process the message started, and their events"
}

held_by_service() {
  printf '%s\n' "$1" | grep -A 40 "NotificationRecord(.* id=$2 tag=null " | sed '/^ *NotificationRecord(/{1!q;}' |
    grep -q "FOREGROUND_SERVICE"
}

recorder_check() {
  [ "$watch" = 1 ] && { echo "recorder: not checked, a watch does not list an ongoing notification"; return 0; }
  started=$(scenario recorder)
  # A build without the recorder answers {"ok":{"unknown":"recorder"}}.
  if [ "$(field "$started" "a.get('ok', {}).get('unknown')")" = recorder ]; then
    [ "$(cat "$out/targets" 2>/dev/null)" = true ] && fail "recorder: a build with targets has no recorder"
    echo "recorder: not in this build"
    return 0
  fi
  [ "$(field "$started" "'id' in a.get('ok', {})")" = True ] || fail "recorder: $started"
  recorder=$(field "$started" "a['ok']['id']")
  # The service is not running: the activity's notification is an ordinary one until the tap starts it.
  wait_for "the recorder's activity is shown" 'dump | grep -c "NotificationRecord(.* id=$ACTIVITY_ID tag=null " >/dev/null'
  tapped=$(tap Record)
  case $tapped in *"actionId=record, withheld=false"*) ;; *) fail "recorder: the tap did not reach the service: $tapped" ;; esac
  case $tapped in *'"kind":"handled"'*) ;; *) fail "recorder: Rust did not drain the tap as handled: $tapped" ;; esac
  wait_for "the service holds the activity at revision 1" \
    'now=$(dump) && held_by_service "$now" "$ACTIVITY_ID" && printf "%s\n" "$now" | grep -A 60 "id=$ACTIVITY_ID tag=null " | grep -q "\"revision\":1"'
  ended=$(scenario end)
  [ "$(field "$ended" "len(a['ok'])")" = 1 ] || fail "recorder end: $ended"
  wait_for "the service keeps its own notification" 'now=$(dump) && held_by_service "$now" "$FALLBACK_ID"'
  echo "recorder: a tap reached the service, Rust drained it as handled, and at the end the service kept its own"
}

# The center of the first node on the screen whose text is [1], any case; empty when there is none.
node_at() {
  device shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1
  device exec-out cat /sdcard/window_dump.xml | python3 -I -c '
import re, sys
want = sys.argv[1].lower()
for node in re.finditer(r"<node [^>]*>", sys.stdin.read()):
    text = re.search(r" text=\"([^\"]*)\"", node.group(0))
    if text and text.group(1).lower() == want:
        b = [int(v) for v in re.findall(r"\d+", re.search(r"bounds=\"([^\"]*)\"", node.group(0)).group(1))]
        print((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
        break' "$1"
  device shell rm -f /sdcard/window_dump.xml
}

# Taps the button titled [1] in the shade. A notification that the shade shows collapsed hides its buttons:
# the one titled [2] (the recorder's by default) is dragged open, and the shade is read again.
tap() {
  title=$1 card=${2:-Consumer recorder}
  device logcat -c 2>/dev/null || true
  at=
  for _ in 1 2 3 4; do
    device shell cmd statusbar expand-notifications
    sleep 2
    at=$(node_at "$title")
    [ -n "$at" ] && break
    open=$(node_at "$card")
    if [ -n "$open" ]; then
      # shellcheck disable=SC2086
      set -- $open
      device shell input swipe "$1" "$2" "$1" $(($2 + 600)) 400
      sleep 2
      at=$(node_at "$title")
      [ -n "$at" ] && break
    fi
    device shell cmd statusbar collapse
    sleep 1
  done
  [ -n "$at" ] || fail "no button titled $title in the shade"
  # shellcheck disable=SC2086
  device shell input tap $at
  sleep 5
  device shell cmd statusbar collapse
  device logcat -d -v tag -s "$tag:I" | tr -d '\r' | sed -n "s/^I\/$tag: //p" | grep -vE '^[a-z-]+#[0-9]+ '
}

command=${1:-}
[ $# -eq 0 ] || shift
case $command in
  build) build ;;
  run) run ;;
  scenario) scenario "$@" ;;
  push) [ "$#" = 1 ] || fail "push takes the member"; push "$1" ;;
  tap) tap "$@" ;;
  uninstall) device uninstall "$package" ;;
  *) echo "usage: consumer.sh build | run | scenario NAME [JSON] | tap TITLE | uninstall" >&2; exit 2 ;;
esac
