#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
PLATFORM="$SDK/platforms/android-36/android.jar"
BT="$SDK/build-tools/36.0.0"
OUT="$ROOT_DIR/build"
GEN="$OUT/gen"
CLASSES="$OUT/classes"
DEX="$OUT/dex"
UNSIGNED="$OUT/capture-router-unsigned.apk"
ALIGNED="$OUT/capture-router-aligned.apk"
SIGNED="$OUT/capture-router-debug.apk"
KEYSTORE="$ROOT_DIR/debug.keystore"

rm -rf "$OUT"
mkdir -p "$GEN" "$CLASSES" "$DEX"

"$BT/aapt2" compile --dir "$ROOT_DIR/app/src/main/res" -o "$OUT/res.zip"
"$BT/aapt2" link \
  -I "$PLATFORM" \
  --manifest "$ROOT_DIR/AndroidManifest.xml" \
  --java "$GEN" \
  --auto-add-overlay \
  -o "$UNSIGNED" \
  "$OUT/res.zip"

find "$ROOT_DIR/app/src/main/java" "$GEN" -name '*.java' > "$OUT/sources.list"
javac -encoding UTF-8 -source 8 -target 8 -classpath "$PLATFORM" \
  -d "$CLASSES" @"$OUT/sources.list"

"$BT/d8" --min-api 26 --lib "$PLATFORM" --output "$DEX" $(find "$CLASSES" -name '*.class')
cd "$DEX"
zip -qr "$UNSIGNED" classes.dex
cd "$ROOT_DIR"

"$BT/zipalign" -f -p 4 "$UNSIGNED" "$ALIGNED"

if [[ ! -f "$KEYSTORE" ]]; then
  keytool -genkeypair -v \
    -keystore "$KEYSTORE" \
    -storepass android \
    -alias androiddebugkey \
    -keypass android \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null
fi

"$BT/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$SIGNED" \
  "$ALIGNED"

"$BT/apksigner" verify "$SIGNED"
echo "$SIGNED"
