#!/usr/bin/env bash
# Media3 has Android resources/dependencies: build with AGP, not raw javac/D8.
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
source "$PROJECT/tools/gradle-command.sh"
"${VEGAS_GRADLE_COMMAND[@]}" --no-daemon --console=plain -p "$PROJECT" :app:assembleDebug
OUT="$PROJECT/build/sdk"
mkdir -p "$OUT"
VERSION="$(python3 - "$PROJECT/app/build.gradle" <<'VERSION_PY'
import pathlib,re,sys
text=pathlib.Path(sys.argv[1]).read_text()
print(re.search(r"versionName\s+'([^']+)'",text).group(1))
VERSION_PY
)"
APK="$OUT/Sony_Vegas_Video_v$VERSION.apk"
cp "$PROJECT/app/build/outputs/apk/debug/app-debug.apk" "$APK"
python3 "$PROJECT/tools/check-apk.py" "$APK"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
TOOLS="$SDK/build-tools/35.0.0"
if [[ -x "$TOOLS/apksigner" ]]; then
    "$TOOLS/apksigner" verify --verbose "$APK"
    "$TOOLS/zipalign" -c -P 16 4 "$APK"
    "$TOOLS/aapt2" dump badging "$APK"
fi
echo "$APK"
