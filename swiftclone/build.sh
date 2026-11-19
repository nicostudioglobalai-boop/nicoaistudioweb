#!/bin/bash
set -e
ROOT="/workspace/android-build"
PROJ="/workspace/swiftclone"
BT=$(ls -d $ROOT/tools/bt/android-*)
PLATFORM_JAR=$(ls $ROOT/tools/platform/android-*/android.jar | head -1)
export JAVA_HOME=$(ls -d $ROOT/tools/jdk-17.*)
export PATH="$JAVA_HOME/bin:$PATH"

OUT="$PROJ/build"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/6] aapt2 compile resources"
"$BT/aapt2" compile --dir "$PROJ/res" -o "$OUT/resources.zip"

echo "[2/6] aapt2 link"
"$BT/aapt2" link -o "$OUT/unsigned.apk" \
  -I "$PLATFORM_JAR" \
  --manifest "$PROJ/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 24 --target-sdk-version 34 \
  --auto-add-overlay \
  "$OUT/resources.zip"

echo "[3/6] javac"
find "$PROJ/src" -name '*.java' > "$OUT/sources.txt"
find "$OUT/gen" -name '*.java' >> "$OUT/sources.txt"
javac -source 1.8 -target 1.8 \
  -bootclasspath "$PLATFORM_JAR" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt" 2>&1 | grep -v -E 'bootstrap class path|source value|target value' || true

echo "[4/6] d8 dex"
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --release \
  --lib "$PLATFORM_JAR" \
  --min-api 24 \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

echo "[5/6] package + zipalign"
python3 - "$OUT/unsigned.apk" "$OUT/unaligned.apk" "$OUT/dex/classes.dex" <<'PYEOF'
import sys, zipfile, shutil
src, dst, dex = sys.argv[1], sys.argv[2], sys.argv[3]
shutil.copyfile(src, dst)
with zipfile.ZipFile(dst, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
PYEOF
"$BT/zipalign" -f 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

echo "[6/6] sign"
KS="$PROJ/swiftclone.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias swiftclone \
    -storepass swiftclone123 -keypass swiftclone123 \
    -dname "CN=SwiftClone, O=SwiftClone" -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
java -jar "$BT/lib/apksigner.jar" sign \
  --ks "$KS" --ks-key-alias swiftclone \
  --ks-pass pass:swiftclone123 --key-pass pass:swiftclone123 \
  --out "$PROJ/SwiftClone-v1.1.apk" "$OUT/aligned.apk"

echo "verify:"
java -jar "$BT/lib/apksigner.jar" verify --print-certs "$PROJ/SwiftClone-v1.1.apk" | head -2
ls -la "$PROJ/SwiftClone-v1.1.apk"
