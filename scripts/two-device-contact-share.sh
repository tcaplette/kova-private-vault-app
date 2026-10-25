#!/usr/bin/env bash
# P4-5's rig, task 6.4 — a contact share between two real installs.
#
# Two devices, two identity files, two Keystores, and a container that crosses
# between them as bytes. The plaintext never travels: both sides derive it from a
# fixed seed, so B asserting equality is B decrypting what A encrypted rather than
# this script carrying an answer between them.
#
# **It drives `am instrument` rather than `connectedAndroidTest`, and that is
# load-bearing.** Gradle uninstalls the app after every instrumentation run, which
# takes `identity.bin` and any staged container with it — the first version of this
# script failed for exactly that reason. Installing once and invoking the runner
# directly is what lets state survive between the steps.
#
#   scripts/two-device-contact-share.sh --a emulator-5554 --b emulator-5556
set -euo pipefail

A=""; B=""
while [ $# -gt 0 ]; do
  case "$1" in
    --a) A="$2"; shift 2 ;;
    --b) B="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -n "$A" ] && [ -n "$B" ] || { echo "usage: $0 --a <serial> --b <serial>" >&2; exit 2; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
PKG=com.app.vault
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
CONTAINER=two-device.bin
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

instrument() {   # serial, class#method, extra -e pairs...
  local serial="$1"; shift
  local spec="$1"; shift
  local out="$WORK/$(echo "$spec" | tr '#' '.').log"
  adb -s "$serial" shell am instrument -w -e class "$spec" "$@" "$RUNNER" > "$out" 2>&1
  if grep -q "FAILURES\!\!\!\|INSTRUMENTATION_CODE: 0" "$out"; then
    echo "FAILED: $spec on $serial" >&2; sed -n '1,40p' "$out" >&2; exit 1
  fi
  grep -E "OK \(|Tests run" "$out" | tail -1
}

echo "== 0. build and install on both devices =="
( cd "$ROOT" && ./gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest )
for S in "$A" "$B"; do
  adb -s "$S" install -r -t "$APK" >/dev/null
  adb -s "$S" install -r -t "$TEST_APK" >/dev/null
  adb -s "$S" shell pm grant "$PKG" android.permission.CAMERA 2>/dev/null || true
done

echo "== 1. B's real pairing code, from B's own store =="
adb -s "$B" logcat -c
instrument "$B" com.app.vault.PairingSceneTest#printThisInstallsPairingCode
B_PAYLOAD="$(adb -s "$B" logcat -d -s PairingScene:I | sed -n 's/.*PAYLOAD \([0-9a-f]*\).*/\1/p' | tail -1)"
[ -n "$B_PAYLOAD" ] || { echo "could not read B's payload" >&2; exit 1; }
echo "   B: $B_PAYLOAD"

echo "== 2. A seals an item to that key =="
adb -s "$A" logcat -c
instrument "$A" com.app.vault.TwoDeviceShareTest#sealForRecipient -e recipient.payload "$B_PAYLOAD"
adb -s "$A" logcat -d -s TwoDeviceShare:I | tail -1

echo "== 3. the container crosses, as bytes =="
adb -s "$A" exec-out run-as "$PKG" cat "/data/data/$PKG/files/$CONTAINER" > "$WORK/$CONTAINER"
[ -s "$WORK/$CONTAINER" ] || { echo "the container did not come off A" >&2; exit 1; }
adb -s "$B" push "$WORK/$CONTAINER" "/data/local/tmp/$CONTAINER" >/dev/null
adb -s "$B" shell chmod 644 "/data/local/tmp/$CONTAINER"
echo "   $(stat -c %s "$WORK/$CONTAINER") bytes A → B"

echo "== 4. B opens it with B's own identity, and a stranger cannot =="
adb -s "$B" logcat -c
instrument "$B" com.app.vault.TwoDeviceShareTest
adb -s "$B" logcat -d -s TwoDeviceShare:I | tail -1

echo "== 5. leaving both devices as they were =="
adb -s "$A" shell run-as "$PKG" rm -f "/data/data/$PKG/files/$CONTAINER" 2>/dev/null || true
adb -s "$B" shell rm -f "/data/local/tmp/$CONTAINER" || true
for S in "$A" "$B"; do adb -s "$S" shell run-as "$PKG" rm -f "/data/data/$PKG/files/identity.bin" 2>/dev/null || true; done
echo "done — logs in $WORK"
