#!/usr/bin/env bash
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
PACKAGE=com.personal.sonyvegasvideo
RUNNER="$PACKAGE.test/$PACKAGE.ExportInstrumentation"
OUT="$PROJECT/build/device-test-results"
mkdir -p "$OUT/screenshots"
APP="${VEGAS_APP_APK:-$PROJECT/build/sdk/Sony_Vegas_Video_v2.4.apk}"
TEST="${VEGAS_TEST_APK:-$PROJECT/build/device-tests/VegasVideo_device_tests.apk}"
[[ -f "$APP" && -f "$TEST" ]] || { echo 'Build/download both APKs first.' >&2; exit 1; }
adb wait-for-device
adb install -r "$APP"
adb install -r -t "$TEST"
adb logcat -c
FAILURES=0
collect() {
    adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>&1 || true
    adb shell dumpsys activity activities > "$OUT/activities.txt" 2>&1 || true
    adb exec-out screencap -p > "$OUT/screenshots/last-screen.png" 2>/dev/null || true
    while IFS= read -r FILE; do
        FILE="${FILE%$'\r'}"
        [[ "$FILE" =~ ^(ui|qa|effect)[A-Za-z0-9._-]*\.png$ ]] || continue
        adb exec-out run-as "$PACKAGE" cat "files/$FILE" > "$OUT/screenshots/$FILE" 2>/dev/null || true
    done < <(adb shell run-as "$PACKAGE" ls files 2>/dev/null || true)
}
trap collect EXIT
run_case() {
    local NAME="$1" LIMIT="$2"
    shift 2
    local LOG="$OUT/$NAME.txt"
    local TIMEOUT=()
    if command -v timeout >/dev/null 2>&1; then TIMEOUT=(timeout --foreground "${LIMIT}s"); fi
    echo "Running $NAME"
    if ! "${TIMEOUT[@]}" adb shell am instrument -w -r "$@" "$RUNNER" 2>&1 | tee "$LOG"; then
        echo "$NAME: command failed or timed out" | tee -a "$OUT/summary.txt"
        adb exec-out screencap -p > "$OUT/screenshots/failure-$NAME.png" 2>/dev/null || true
        adb logcat -d -v threadtime > "$OUT/$NAME-logcat.txt" 2>&1 || true
        adb shell am force-stop "$PACKAGE" || true
        return 1
    fi
    if ! grep -q 'PASS:' "$LOG" || ! grep -q '^INSTRUMENTATION_CODE: -1' "$LOG"; then
        echo "$NAME: FAIL (see $NAME.txt)" | tee -a "$OUT/summary.txt"
        adb exec-out screencap -p > "$OUT/screenshots/failure-$NAME.png" 2>/dev/null || true
        adb logcat -d -v threadtime > "$OUT/$NAME-logcat.txt" 2>&1 || true
        adb shell am force-stop "$PACKAGE" || true
        return 1
    fi
    echo "$NAME: PASS" | tee -a "$OUT/summary.txt"
}
: > "$OUT/summary.txt"
run_case regression 300 || FAILURES=$((FAILURES+1))
run_case effects 900 -e mode effects || FAILURES=$((FAILURES+1))
adb shell wm size 1768x2208
adb shell wm density 320
adb shell settings put system font_scale 1.0
run_case full-wide 300 -e mode full -e screenshot wide || FAILURES=$((FAILURES+1))
adb shell wm size 1080x2400
adb shell wm density 420
run_case full-compact 300 -e mode full -e screenshot compact || FAILURES=$((FAILURES+1))
adb shell wm size reset
adb shell wm density reset
cat "$OUT/summary.txt"
[[ "$FAILURES" == 0 ]]
