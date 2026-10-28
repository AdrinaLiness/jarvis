#!/bin/bash
# Сборка J.A.R.V.I.S. Android APK без Gradle.
# Нужны: JDK 17+, Android build-tools 34 (aapt2, d8, zipalign, apksigner)
# и platform android-34 (android.jar).
#
# Пути задаются переменными окружения:
#   ANDROID_BUILD_TOOLS   — напр. $ANDROID_HOME/build-tools/34.0.0
#   ANDROID_PLATFORM_JAR  — напр. $ANDROID_HOME/platforms/android-34/android.jar
set -e
BT="${ANDROID_BUILD_TOOLS:-$HOME/android-sdk/build-tools/34.0.0}"
PLAT="${ANDROID_PLATFORM_JAR:-$HOME/android-sdk/platforms/android-34/android.jar}"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/build"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "[1/5] javac..."
javac --release 8 -nowarn -classpath "$PLAT" \
  -d "$OUT/classes" "$HERE"/src/com/adrina/jarvis/*.java

echo "[2/5] d8 (dex)..."
"$BT/d8" --release --lib "$PLAT" --min-api 24 \
  --output "$OUT/dex" $(find "$OUT/classes" -name "*.class")

echo "[3/5] aapt2..."
"$BT/aapt2" compile --dir "$HERE/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/jarvis-unsigned.apk" -I "$PLAT" \
  --manifest "$HERE/AndroidManifest.xml" -A "$HERE/assets" \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code 1 --version-name 1.0 \
  --auto-add-overlay "$OUT/res.zip"

echo "[4/5] classes.dex → apk, zipalign..."
(cd "$OUT/dex" && zip -q -u "$OUT/jarvis-unsigned.apk" classes.dex)
"$BT/zipalign" -f 4 "$OUT/jarvis-unsigned.apk" "$OUT/jarvis-aligned.apk"

echo "[5/5] подпись..."
KS="$HERE/jarvis.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias jarvis -keyalg RSA -keysize 2048 \
    -validity 10950 -storepass jarvis2026 -keypass jarvis2026 \
    -dname "CN=JARVIS, OU=AdrinaLiness, O=AdrinaLiness, C=RU" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:jarvis2026 \
  --out "$HERE/JARVIS.apk" "$OUT/jarvis-aligned.apk"
"$BT/apksigner" verify "$HERE/JARVIS.apk"

echo "Готово: $HERE/JARVIS.apk"
