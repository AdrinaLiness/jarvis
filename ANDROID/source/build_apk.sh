#!/bin/bash
# Сборка J.A.R.V.I.S. Android APK (без Gradle — aapt2 + d8 + apksigner)
# v1.6: герцовка до 120 Гц — реактор/анимации без лагов (preferredDisplayModeId)
# v1.5: смс/мессенджеры/звонки — SmsManager, deep links, контакты, TTS в фоне
# v1.4: только телефон — запуск ВСЕХ приложений на телефоне, ПК-команды удалены
# v1.3: проверка обновлений при каждом запуске (диалог «Доступно обновление»)
# v1.2: уведомления/напоминания (NotifReceiver + R.drawable.notif_icon → нужен R.java)
set -e
SDK=/home/z/my-project/android-sdk
PROJ=/home/z/my-project/jarvis_android
BT=$SDK/android-14
PLAT=$SDK/android-34/android.jar
# JDK: предпочитаем pinned 17, иначе системный javac/java
if [ -x "$SDK/jdk-17.0.21+9/bin/javac" ]; then
  JAVA=$SDK/jdk-17.0.21+9/bin
else
  JAVA="$(dirname "$(command -v javac)")"
fi
OUT=$PROJ/out
export PATH="$JAVA:$PATH"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen" "$PROJ/build"

echo "[1/6] aapt2 compile + link (+ R.java)..."
"$BT/aapt2" compile --dir "$PROJ/res" -o "$OUT/res.zip"
"$BT/aapt2" link \
  -o "$OUT/jarvis-unsigned.apk" \
  -I "$PLAT" \
  --manifest "$PROJ/AndroidManifest.xml" \
  -A "$PROJ/assets" \
  --java "$OUT/gen" \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code 7 --version-name 1.6 --replace-version \
  --auto-add-overlay \
  "$OUT/res.zip"
ls "$OUT/gen/com/adrina/jarvis/" 2>/dev/null

echo "[2/6] javac..."
"$JAVA/javac" --release 8 -nowarn -classpath "$PLAT" \
  -d "$OUT/classes" \
  "$OUT/gen/com/adrina/jarvis/R.java" \
  "$PROJ"/src/com/adrina/jarvis/*.java
ls "$OUT/classes/com/adrina/jarvis/" | head -5

echo "[3/6] d8 (dex)..."
"$BT/d8" --release --lib "$PLAT" --min-api 24 \
  --output "$OUT/dex" $(find "$OUT/classes" -name "*.class")
ls -la "$OUT/dex/"

echo "[4/6] classes.dex → apk..."
cd "$OUT/dex" && zip -q -u "$OUT/jarvis-unsigned.apk" classes.dex && cd "$SDK"

echo "[5/6] zipalign..."
"$BT/zipalign" -f 4 "$OUT/jarvis-unsigned.apk" "$OUT/jarvis-aligned.apk"

echo "[6/6] подпись..."
KS=$PROJ/build/jarvis.keystore
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias jarvis -keyalg RSA -keysize 2048 \
    -validity 10950 -storepass jarvis2026 -keypass jarvis2026 \
    -dname "CN=JARVIS, OU=AdrinaLiness, O=AdrinaLiness, C=RU" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:jarvis2026 \
  --out "$PROJ/JARVIS-v1.6.apk" "$OUT/jarvis-aligned.apk"
"$BT/apksigner" verify "$PROJ/JARVIS-v1.6.apk" && echo "APK OK"

ls -la "$PROJ/JARVIS-v1.6.apk"
"$BT/aapt2" dump badging "$PROJ/JARVIS-v1.6.apk" | head -8
