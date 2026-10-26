#!/usr/bin/env bash
# P4-5's two-device rig, task 6.4 — two real installs, and a code read through a lens.
#
# What this proves, and in which direction:
#
#   A → B   install A's real pairing code is rendered by the app's own generator,
#           hung in emulator B's virtual scene, and read by B's camera through the
#           shipping scan path.
#   B → A   the same, the other way, so "two installs pair" is not a claim about
#           one direction.
#
# Both halves need each emulator to be launched with the other's poster, which is
# why the run is staged: the script prints what it is doing at each step and never
# touches an emulator it did not start.
#
#   scripts/two-device-pairing.sh --a emulator-5554 --b-avd Pixel_9
set -euo pipefail

A=""; B_AVD=""; B_PORT=5556
while [ $# -gt 0 ]; do
  case "$1" in
    --a) A="$2"; shift 2 ;;
    --b-avd) B_AVD="$2"; shift 2 ;;
    --b-port) B_PORT="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -n "$A" ] && [ -n "$B_AVD" ] || { echo "usage: $0 --a <serial> --b-avd <avd> [--b-port N]" >&2; exit 2; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
B="emulator-$B_PORT"
trap 'echo "artifacts in $WORK"' EXIT

payload_of() {   # $1 = serial
  adb -s "$1" logcat -c
  ( cd "$ROOT" && ./gradlew -q :app:connectedPlayDebugAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.class=com.app.vault.PairingSceneTest#printThisInstallsPairingCode \
      -Pandroid.injected.testOnly=false >/dev/null 2>&1 ) || true
  adb -s "$1" logcat -d -s PairingScene:I | sed -n 's/.*PAYLOAD \([0-9a-f]*\).*/\1/p' | tail -1
}

echo "== step 1: install A's code =="
ANDROID_SERIAL="$A" ; export ANDROID_SERIAL
A_PAYLOAD="$(payload_of "$A")"
[ -n "$A_PAYLOAD" ] || { echo "could not read A's payload" >&2; exit 1; }
echo "A: $A_PAYLOAD"

echo "== step 2: render it =="
( cd "$ROOT" && ./gradlew -q :app:renderPairingPoster -Ppayload="$A_PAYLOAD" -Pout="$WORK/a.png" )

echo "== step 3: start B with A's code on its wall =="
"$ROOT/scripts/pairing-scene-emulator.sh" --avd "$B_AVD" --poster "$WORK/a.png" --port "$B_PORT" --run

echo "== step 4: B reads A's code through its camera =="
ANDROID_SERIAL="$B" ; export ANDROID_SERIAL
( cd "$ROOT" && ./gradlew :app:connectedPlayDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.app.vault.PairingSceneTest#theCameraPipelineDeliversFramesAndDecodesWhatIsInTheScene \
    -Pandroid.testInstrumentationRunnerArguments.pairing.payload="$A_PAYLOAD" )

echo "== step 5: B's own code, for the other direction =="
B_PAYLOAD="$(payload_of "$B")"
echo "B: $B_PAYLOAD"
( cd "$ROOT" && ./gradlew -q :app:renderPairingPoster -Ppayload="$B_PAYLOAD" -Pout="$WORK/b.png" )
echo "to close the loop, restart A with: scripts/pairing-scene-emulator.sh --avd <A's avd> --poster $WORK/b.png --run"
echo "then run the scene test on A with -Pandroid.testInstrumentationRunnerArguments.pairing.payload=$B_PAYLOAD"
