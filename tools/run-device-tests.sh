#!/usr/bin/env bash
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
PACKAGE=com.personal.sonyvegasvideo
RUNNER="$PACKAGE.test/$PACKAGE.ExportInstrumentation"
OUT="$PROJECT/build/device-test-results"
mkdir -p "$OUT/screenshots" "$OUT/diagnostics"
APP="${VEGAS_APP_APK:-$PROJECT/build/sdk/Sony_Vegas_Video_v2.4.apk}"
TEST="${VEGAS_TEST_APK:-$PROJECT/build/device-tests/VegasVideo_device_tests.apk}"
[[ -f "$APP" && -f "$TEST" ]] || { echo 'Build/download both APKs first.' >&2; exit 1; }
adb wait-for-device
adb install -r "$APP"
adb install -r -t "$TEST"
adb logcat -c
FAILURES=0
# Preserve any caller's overrides, not merely the physical emulator defaults.
ORIGINAL_SIZE="$(adb shell wm size | tr -d '\r')"
ORIGINAL_DENSITY="$(adb shell wm density | tr -d '\r')"
ORIGINAL_SIZE_OVERRIDE="$(awk -F': ' '/Override size:/{print $2}' <<< "$ORIGINAL_SIZE")"
ORIGINAL_DENSITY_OVERRIDE="$(awk -F': ' '/Override density:/{print $2}' <<< "$ORIGINAL_DENSITY")"
ORIGINAL_FONT_SCALE="$(adb shell settings get system font_scale | tr -d '\r')"
ORIGINAL_ACCELEROMETER_ROTATION="$(adb shell settings get system accelerometer_rotation | tr -d '\r')"
ORIGINAL_USER_ROTATION="$(adb shell settings get system user_rotation | tr -d '\r')"
DISPLAY_CHANGED=0
collect() {
    adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>&1 || true
    adb shell dumpsys activity activities > "$OUT/activities.txt" 2>&1 || true
    adb exec-out screencap -p > "$OUT/screenshots/last-screen.png" 2>/dev/null || true
    while IFS= read -r FILE; do
        FILE="${FILE%$'\r'}"
        if [[ "$FILE" =~ ^(ui|qa|effect)[A-Za-z0-9._-]*\.png$ ]]; then
            adb exec-out run-as "$PACKAGE" cat "files/$FILE" > "$OUT/screenshots/$FILE" 2>/dev/null || true
        elif [[ "$FILE" =~ ^effect-failed-[A-Za-z0-9._-]+\.(mp4|txt)$ ]]; then
            adb exec-out run-as "$PACKAGE" cat "files/$FILE" > "$OUT/diagnostics/$FILE" 2>/dev/null || true
        fi
    done < <(adb shell run-as "$PACKAGE" ls files 2>/dev/null || true)
}
restore_setting() {
    if [[ "$2" == null || -z "$2" ]]; then adb shell settings delete system "$1" >/dev/null; else adb shell settings put system "$1" "$2"; fi
}
finish() {
    local RESULT=$?
    local RESTORE_FAILED=0
    trap - EXIT
    collect || true
    if [[ "$DISPLAY_CHANGED" == 1 ]]; then
        adb shell wm size "${ORIGINAL_SIZE_OVERRIDE:-reset}" || RESTORE_FAILED=1
        adb shell wm density "${ORIGINAL_DENSITY_OVERRIDE:-reset}" || RESTORE_FAILED=1
        restore_setting font_scale "$ORIGINAL_FONT_SCALE" || RESTORE_FAILED=1
        restore_setting accelerometer_rotation "$ORIGINAL_ACCELEROMETER_ROTATION" || RESTORE_FAILED=1
        restore_setting user_rotation "$ORIGINAL_USER_ROTATION" || RESTORE_FAILED=1
        local RESTORED_SIZE RESTORED_DENSITY
        RESTORED_SIZE="$(adb shell wm size | tr -d '\r')"
        RESTORED_DENSITY="$(adb shell wm density | tr -d '\r')"
        [[ "$RESTORED_SIZE" == "$ORIGINAL_SIZE" && "$RESTORED_DENSITY" == "$ORIGINAL_DENSITY" ]] || RESTORE_FAILED=1
        {
            echo "Original: $ORIGINAL_SIZE / $ORIGINAL_DENSITY"
            echo "Restored: $RESTORED_SIZE / $RESTORED_DENSITY"
        } > "$OUT/display-restored.txt"
        if [[ "$RESTORE_FAILED" == 1 ]]; then
            echo 'restore: FAIL (see display-restored.txt)' | tee -a "$OUT/summary.txt"
            RESULT=1
        fi
    fi
    exit "$RESULT"
}
trap finish EXIT
profile_png_matches() {
    python3 - "$1" "$2" <<'PNG_PROFILE'
import pathlib,struct,sys
data=pathlib.Path(sys.argv[1]).read_bytes()
if len(data)<24 or data[:8]!=b'\x89PNG\r\n\x1a\n' or data[12:16]!=b'IHDR':sys.exit(1)
actual='%dx%d'%struct.unpack('>II',data[16:24])
sys.exit(0 if actual==sys.argv[2] else 1)
PNG_PROFILE
}
set_profile() {
    local NAME="$1" SIZE="$2" DENSITY="$3"
    local META="$OUT/display-$NAME.txt" PNG="$OUT/screenshots/profile-$NAME.png"
    local ACTUAL_SIZE='' ACTUAL_DENSITY='' DEADLINE=0
    DISPLAY_CHANGED=1
    adb shell am force-stop "$PACKAGE"
    if ! {
        echo "Requested profile: $NAME $SIZE density $DENSITY"
        echo "$ORIGINAL_SIZE"
        echo "$ORIGINAL_DENSITY"
        adb shell wm size "$SIZE" &&
        adb shell wm density "$DENSITY" &&
        adb shell settings put system font_scale 1.0 &&
        adb shell settings put system accelerometer_rotation 0 &&
        adb shell settings put system user_rotation 0
    } > "$META" 2>&1; then
        echo "$NAME: FAIL (display command failed; see display-$NAME.txt)" | tee -a "$OUT/summary.txt"
        return 1
    fi
    # Android 11 clamps forced sizes to twice the AVD hardware dimensions. The
    # workflow uses Pixel 2 hardware; verify both WM and the actual framebuffer.
    DEADLINE=$((SECONDS+5))
    while (( SECONDS <= DEADLINE )); do
        ACTUAL_SIZE="$(adb shell wm size | tr -d '\r' | awk -F': ' '/Physical size:/{p=$2}/Override size:/{o=$2}END{print o!=""?o:p}')"
        ACTUAL_DENSITY="$(adb shell wm density | tr -d '\r' | awk -F': ' '/Physical density:/{p=$2}/Override density:/{o=$2}END{print o!=""?o:p}')"
        adb exec-out screencap -p > "$PNG" 2>/dev/null || true
        if [[ "$ACTUAL_SIZE" == "$SIZE" && "$ACTUAL_DENSITY" == "$DENSITY" ]] && profile_png_matches "$PNG" "$SIZE"; then
            echo "Actual profile: $NAME $ACTUAL_SIZE density $ACTUAL_DENSITY; framebuffer verified" | tee -a "$META" "$OUT/summary.txt"
            adb shell dumpsys display >> "$META" 2>&1 || true
            return 0
        fi
        sleep .25
    done
    {
        echo "Profile mismatch: requested $SIZE/$DENSITY, WM reported $ACTUAL_SIZE/$ACTUAL_DENSITY"
        adb shell wm size
        adb shell wm density
        adb shell dumpsys display
    } >> "$META" 2>&1
    echo "$NAME: FAIL (display profile did not apply; see display-$NAME.txt)" | tee -a "$OUT/summary.txt"
    return 1
}
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
        grep -E ' [EF] (VegasPreview|CompositionPlayer(-[0-9]+)?|ExoPlayerImplInternal|DefaultVideoFrameProcessor|VideoFrameProcessingTaskExecutor)' "$OUT/$NAME-logcat.txt" | tail -200 || true
        adb shell am force-stop "$PACKAGE" || true
        return 1
    fi
    if ! grep -q 'PASS:' "$LOG" || ! grep -q '^INSTRUMENTATION_CODE: -1' "$LOG"; then
        echo "$NAME: FAIL (see $NAME.txt)" | tee -a "$OUT/summary.txt"
        adb exec-out screencap -p > "$OUT/screenshots/failure-$NAME.png" 2>/dev/null || true
        adb logcat -d -v threadtime > "$OUT/$NAME-logcat.txt" 2>&1 || true
        grep -E ' [EF] (VegasPreview|CompositionPlayer(-[0-9]+)?|ExoPlayerImplInternal|DefaultVideoFrameProcessor|VideoFrameProcessingTaskExecutor)' "$OUT/$NAME-logcat.txt" | tail -200 || true
        adb shell am force-stop "$PACKAGE" || true
        return 1
    fi
    echo "$NAME: PASS" | tee -a "$OUT/summary.txt"
}
: > "$OUT/summary.txt"
run_case regression 300 || FAILURES=$((FAILURES+1))
if set_profile wide 1768x2208 320; then
    run_case full-wide 300 -e mode full -e screenshot wide -e expectedWidth 1768 -e expectedHeight 2208 -e expectedDensity 320 || FAILURES=$((FAILURES+1))
else FAILURES=$((FAILURES+1)); fi
if set_profile compact 1080x2400 420; then
    run_case full-compact 300 -e mode full -e screenshot compact -e expectedWidth 1080 -e expectedHeight 2400 -e expectedDensity 420 || FAILURES=$((FAILURES+1))
else FAILURES=$((FAILURES+1)); fi
run_case effects 900 -e mode effects || FAILURES=$((FAILURES+1))
cat "$OUT/summary.txt"
[[ "$FAILURES" == 0 ]]
