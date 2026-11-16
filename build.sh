#!/bin/bash
# Build the Skyble Tube APK without Gradle / Android Studio.
set -e
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$PWD/sdk}"
if [ ! -f "$SDK/android.jar" ] && [ -f "$PWD/../skyble-browser/sdk/android.jar" ]; then
  SDK="$PWD/../skyble-browser/sdk"
fi
AJ="$SDK/android.jar"
R8="$SDK/r8.jar"
if [ ! -f "$AJ" ] && [ -n "$ANDROID_HOME" ]; then
  AJ="$(ls -d "$ANDROID_HOME"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
fi
if [ ! -f "$R8" ] && [ -n "$ANDROID_HOME" ]; then
  R8="$(ls -d "$ANDROID_HOME"/build-tools/*/lib/d8.jar 2>/dev/null | sort -V | tail -1)"
fi
if [ ! -f "$AJ" ] || [ ! -f "$R8" ]; then
  echo "android.jar / r8.jar not found: put them in ./sdk or $PWD/../skyble-browser/sdk" >&2
  exit 1
fi

OUT="$PWD/build"
NAME="SkybleTube-1.2.apk"
KS="$PWD/keystore.jks"

LIBS="$PWD/libs"
# NewPipeExtractor and what it needs at runtime
NPE_JARS="$LIBS/newpipe-extractor-v0.24.6-androidclient.jar $LIBS/jsoup-1.19.1.jar $LIBS/rhino-1.8.0.jar $LIBS/jsr305-3.0.2.jar $LIBS/nanojson-1.7-teamnewpipe-1d9e1ae.jar"
CP="$AJ:$(echo "$NPE_JARS" | tr ' ' ':')"
# core library desugaring: config for D8, jar that L8 turns into the j$ runtime
DESUGAR_JSON="$LIBS/desugar/desugar_jdk_libs.json"
DESUGAR_JAR="$LIBS/desugar_jdk_libs-2.0.4.jar"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/dexlib"

echo "==> 1/7 resources"
aapt package -f -m \
  -J "$OUT/gen" \
  -M AndroidManifest.xml \
  -S res \
  -I "$AJ" \
  --min-sdk-version 21 --target-sdk-version 34 \
  -F "$OUT/base.apk"

echo "==> 2/7 compile java"
find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
if ! javac -nowarn -source 11 -target 11 -classpath "$CP" -d "$OUT/classes" \
  @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1; then
  cat "$OUT/javac.log"
  exit 1
fi
grep -v "bootstrap class path" "$OUT/javac.log" || true

echo "==> 3/7 dex app"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
if ! java -cp "$R8" com.android.tools.r8.D8 \
  --min-api 21 --lib "$AJ" --desugared-lib "$DESUGAR_JSON" \
  --output "$OUT/dex" @"$OUT/classes.txt" $NPE_JARS > "$OUT/d8.log" 2>&1; then
  grep -v "^Warning" "$OUT/d8.log" | tail -30
  exit 1
fi
grep -v "^Warning" "$OUT/d8.log" | head -5 || true

echo "==> 4/7 dex desugared library runtime (j\$ backports)"
if ! java -cp "$R8" com.android.tools.r8.L8 \
  --release --min-api 21 --lib "$AJ" --desugared-lib "$DESUGAR_JSON" \
  --output "$OUT/dexlib" "$DESUGAR_JAR" > "$OUT/l8.log" 2>&1; then
  grep -v "^Warning" "$OUT/l8.log" | tail -30
  exit 1
fi

echo "==> 5/7 pack"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -j "$OUT/unsigned.apk" classes.dex)
count=$(ls "$OUT"/dex/classes*.dex | wc -l)
if [ -f "$OUT/dexlib/classes.dex" ]; then
  cp "$OUT/dexlib/classes.dex" "$OUT/dexlib/classes$((count + 1)).dex"
  zip -q -j "$OUT/unsigned.apk" "$OUT/dexlib/classes$((count + 1)).dex"
fi
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "==> 6/7 sign"
if [ ! -f "$KS" ]; then
  echo "keystore not found: $KS" >&2
  exit 1
fi
apksigner sign --ks "$KS" --ks-pass pass:bangla123 --key-pass pass:bangla123 \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$NAME" "$OUT/aligned.apk"

echo "==> 7/7 verify"
apksigner verify --print-certs "$NAME" | head -5
ls -lh "$NAME"
