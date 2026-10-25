#!/usr/bin/env bash
# P4-5's two-device rig, task 6.2 — launch an AVD with a pairing code on its wall.
#
# The emulator's back camera on these AVDs is `virtualscene`, which renders a 3D
# room the emulated camera photographs. `-virtualscene-poster wall=<png>` hangs an
# image in that room, so the app's real camera pipeline — camera2 session, YUV
# ImageReader, stride-padded plane, decoder — sees a code rather than a buffer a
# test handed it.
#
# **Emulator lifecycle is the operator's.** With --dry-run (the default when no
# --run is given) this prints the command and exits; nothing is started, and
# nothing already running is touched.
#
#   scripts/pairing-scene-emulator.sh --avd Pixel_8 --poster /tmp/code.png [--run]
set -euo pipefail

AVD=""; POSTER=""; RUN=0; PORT=5556
while [ $# -gt 0 ]; do
  case "$1" in
    --avd) AVD="$2"; shift 2 ;;
    --poster) POSTER="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --run) RUN=1; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -n "$AVD" ] && [ -n "$POSTER" ] || { echo "usage: $0 --avd <name> --poster <png> [--port N] [--run]" >&2; exit 2; }
[ -f "$POSTER" ] || { echo "no such poster: $POSTER" >&2; exit 1; }

EMULATOR="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}/emulator/emulator"
CMD=("$EMULATOR" -avd "$AVD" -port "$PORT" -virtualscene-poster "wall=$POSTER" -no-snapshot-load)

printf 'command:'; printf ' %q' "${CMD[@]}"; printf '\n'
if [ "$RUN" -eq 0 ]; then
  echo "(dry run — pass --run to start it)"
  exit 0
fi

"${CMD[@]}" >/dev/null 2>&1 &
SERIAL="emulator-$PORT"
echo "waiting for $SERIAL to boot..."
adb -s "$SERIAL" wait-for-device
until [ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  sleep 2
done
echo "$SERIAL ready"
