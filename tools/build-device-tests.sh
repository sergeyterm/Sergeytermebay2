#!/usr/bin/env bash
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
PLATFORM="${VEGAS_ANDROID_JAR:-${ANDROID_HOME:-}/platforms/android-35/android.jar}"
TOOLS="${VEGAS_BUILD_TOOLS:-${ANDROID_HOME:-}/build-tools/35.0.0}"
OUT="$PROJECT/build/device-tests"
[[ -d "$PROJECT/build/sdk/classes" ]] || { echo 'Run tools/build-sdk.sh first.' >&2; exit 1; }
rm -rf "$OUT/classes" "$OUT/dex"
mkdir -p "$OUT/classes" "$OUT/dex"
cat > "$OUT/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.personal.sonyvegasvideo.test">
    <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="35" />
    <instrumentation android:name="com.personal.sonyvegasvideo.ExportInstrumentation" android:targetPackage="com.personal.sonyvegasvideo" />
    <application android:label="Vegas Video device tests" android:debuggable="true" />
</manifest>
XML
"$TOOLS/aapt2" link -I "$PLATFORM" --manifest "$OUT/AndroidManifest.xml" \
    -A "$PROJECT/app/src/androidTest/assets" -o "$OUT/unsigned.apk"
mapfile -t SOURCES < <(find "$PROJECT/app/src/androidTest/java" -name '*.java' | sort)
java com.sun.tools.javac.Main --release 8 -encoding UTF-8 \
    -classpath "$PLATFORM:$PROJECT/build/sdk/classes" -d "$OUT/classes" "${SOURCES[@]}"
python3 - "$OUT" <<'PY'
import pathlib,sys,zipfile
out=pathlib.Path(sys.argv[1])
with zipfile.ZipFile(out/'classes.jar','w') as z:
 for f in sorted((out/'classes').rglob('*.class')):z.write(f,f.relative_to(out/'classes'))
PY
"$TOOLS/d8" --min-api 29 --lib "$PLATFORM" --classpath "$PROJECT/build/sdk/classes.jar" \
    --output "$OUT/dex" "$OUT/classes.jar"
python3 - "$OUT" <<'PY'
import pathlib,sys,zipfile
out=pathlib.Path(sys.argv[1])
with zipfile.ZipFile(out/'unsigned.apk','a') as z:
 for f in sorted((out/'dex').glob('*.dex')):z.write(f,f.name)
PY
"$TOOLS/zipalign" -P 16 -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$TOOLS/apksigner" sign --ks "$PROJECT/signing/vegas-debug.keystore" --ks-pass pass:android \
    --key-pass pass:android --out "$OUT/VegasVideo_device_tests.apk" "$OUT/aligned.apk"
"$TOOLS/apksigner" verify --verbose "$OUT/VegasVideo_device_tests.apk"
echo "Device test APK: $OUT/VegasVideo_device_tests.apk (compiled; run on a device to obtain test results)"
