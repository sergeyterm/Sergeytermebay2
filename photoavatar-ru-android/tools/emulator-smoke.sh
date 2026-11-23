#!/usr/bin/env bash
# The emulator action executes each 'script' line in a fresh /bin/sh.
# Keep the entire smoke scenario here so loops and error handling remain intact.
set -euo pipefail
test -f smoke-apk/app-debug.apk
adb install -r smoke-apk/app-debug.apk
adb logcat -c || true
sleep 4
adb shell am start -W -n ru.photoavatarru.app/.RunwayActivity | tee launch.txt
grep -q 'Status: ok' launch.txt
sleep 5
adb shell pidof ru.photoavatarru.app | tee pid.txt
test -s pid.txt
adb shell dumpsys webviewupdate > webview-provider.txt || true
found=0
for n in 1 2 3 4 5 6; do
  adb shell uiautomator dump /sdcard/window.xml || true
  adb pull /sdcard/window.xml window.xml || true
  if [[ -f window.xml ]] && grep -q 'Звонок с ожившим портретом' window.xml; then
    found=1
    break
  fi
  sleep 4
done
adb exec-out screencap -p > app-launch.png || true
adb logcat -d -v brief > full-logcat.txt || true
adb logcat -d -b crash -v brief > crash-log.txt || true
if grep -Eq 'FATAL EXCEPTION|Process: ru.photoavatarru.app' crash-log.txt; then
  echo 'FAIL: fatal Android exception in app' >&2
  exit 1
fi
if [[ "$found" != "1" ]]; then
  echo 'FAIL: WebView content was not visible within 24 seconds' >&2
  grep -iE 'chromium|webview|crash|error|appassets|photoavatar' full-logcat.txt | tail -n 120 || true
  exit 1
fi
echo 'PASS: PhotoAvatar UI visible and no fatal exceptions'
