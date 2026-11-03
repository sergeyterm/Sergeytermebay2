#!/usr/bin/env bash
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
adb install -r "$PROJECT/build/sdk/Sony_Vegas_Video_v2.3.apk"
adb install -r "$PROJECT/build/device-tests/VegasVideo_device_tests.apk"
adb shell am instrument -w -r com.personal.sonyvegasvideo.test/com.personal.sonyvegasvideo.ExportInstrumentation \
    | tee "$PROJECT/build/device-test-results.txt"
grep -q 'PASS:' "$PROJECT/build/device-test-results.txt"
grep -q 'INSTRUMENTATION_CODE: -1' "$PROJECT/build/device-test-results.txt"
