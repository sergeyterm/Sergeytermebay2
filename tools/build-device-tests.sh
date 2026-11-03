#!/usr/bin/env bash
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
source "$PROJECT/tools/gradle-command.sh"
if [[ ! -f "$PROJECT/app/src/androidTest/assets/rotated-av.mp4" ]]; then
    bash "$PROJECT/tools/make-test-fixture.sh"
fi
"${VEGAS_GRADLE_COMMAND[@]}" --no-daemon --console=plain -p "$PROJECT" :app:assembleDebugAndroidTest
OUT="$PROJECT/build/device-tests"
mkdir -p "$OUT"
cp "$PROJECT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" "$OUT/VegasVideo_device_tests.apk"
python3 "$PROJECT/tools/check-apk.py" "$OUT/VegasVideo_device_tests.apk"
echo "$OUT/VegasVideo_device_tests.apk"
