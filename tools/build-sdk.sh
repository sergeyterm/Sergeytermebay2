#!/usr/bin/env bash
# Dependency-free alternative to Gradle, using the official Android SDK tools.
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
PLATFORM="${VEGAS_ANDROID_JAR:-${ANDROID_HOME:-}/platforms/android-35/android.jar}"
TOOLS="${VEGAS_BUILD_TOOLS:-${ANDROID_HOME:-}/build-tools/35.0.0}"
OUT="$PROJECT/build/sdk"
[[ -f "$PLATFORM" && -x "$TOOLS/aapt2" && -x "$TOOLS/d8" ]] || {
    echo 'Set ANDROID_HOME or VEGAS_ANDROID_JAR and VEGAS_BUILD_TOOLS to Android SDK 35.' >&2; exit 1;
}
mkdir -p "$OUT/classes" "$OUT/generated" "$OUT/dex" "$PROJECT/signing"
rm -rf "$OUT/classes" "$OUT/generated" "$OUT/dex"
mkdir -p "$OUT/classes" "$OUT/generated" "$OUT/dex"
python3 - "$PROJECT" "$OUT" <<'PY'
import pathlib,re,sys,xml.etree.ElementTree as E
project,out=map(pathlib.Path,sys.argv[1:])
E.register_namespace('android','http://schemas.android.com/apk/res/android')
manifest=E.parse(project/'app/src/main/AndroidManifest.xml')
manifest.getroot().set('package','com.personal.sonyvegasvideo')
manifest.find('application').set('{http://schemas.android.com/apk/res/android}debuggable','true')
manifest.write(out/'AndroidManifest.xml',encoding='utf-8',xml_declaration=True)
build=(project/'app/build.gradle').read_text()
version=re.search(r'versionCode\s+(\d+)',build).group(1)
name=re.search(r"versionName\s+'([^']+)'",build).group(1)
(out/'version').write_text(version+'\n'+name+'\n')
PY
mapfile -t VERSION < "$OUT/version"
"$TOOLS/aapt2" compile --dir "$PROJECT/app/src/main/res" -o "$OUT/resources.zip"
"$TOOLS/aapt2" link -I "$PLATFORM" --manifest "$OUT/AndroidManifest.xml" \
    "$OUT/resources.zip" -o "$OUT/unsigned.apk" --java "$OUT/generated" \
    --min-sdk-version 29 --target-sdk-version 35 --version-code "${VERSION[0]}" --version-name "${VERSION[1]}"
mapfile -t SOURCES < <(find "$PROJECT/app/src/main/java" "$OUT/generated" -name '*.java' | sort)
java com.sun.tools.javac.Main -encoding UTF-8 --release 8 \
    -classpath "$PLATFORM" -d "$OUT/classes" "${SOURCES[@]}"
python3 - "$OUT" <<'PY'
import pathlib,sys,zipfile
out=pathlib.Path(sys.argv[1])
with zipfile.ZipFile(out/'classes.jar','w') as z:
 for f in sorted((out/'classes').rglob('*.class')):z.write(f,f.relative_to(out/'classes'))
PY
"$TOOLS/d8" --min-api 29 --lib "$PLATFORM" --output "$OUT/dex" "$OUT/classes.jar"
python3 - "$OUT" <<'PY'
import pathlib,sys,zipfile
out=pathlib.Path(sys.argv[1])
with zipfile.ZipFile(out/'unsigned.apk','a') as z:
 for dex in sorted((out/'dex').glob('*.dex')):z.write(dex,dex.name)
PY
KEY="$PROJECT/signing/vegas-debug.keystore"
if [[ ! -f "$KEY" ]]; then
    keytool -genkeypair -keystore "$KEY" -storepass android -keypass android -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10950 -dname 'CN=Vegas Video Debug,O=Personal,C=PL'
fi
"$TOOLS/zipalign" -P 16 -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
APK="$OUT/Sony_Vegas_Video_v${VERSION[1]}.apk"
"$TOOLS/apksigner" sign --ks "$KEY" --ks-pass pass:android --key-pass pass:android --out "$APK" "$OUT/aligned.apk"
"$TOOLS/apksigner" verify --verbose "$APK"
"$TOOLS/zipalign" -c -P 16 4 "$APK"
python3 "$PROJECT/tools/check-apk.py" "$APK"
"$TOOLS/aapt2" dump badging "$APK"
echo "$APK"
