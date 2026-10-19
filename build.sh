#!/data/user/0/app.dsh.mobile/files/engine/bin/bash
# 记分板 APK 一键构建脚本
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
ANDROID="$ROOT/android"
BUILD="$ROOT/build"
AJAR="$HOME/android.jar"
D8="$PREFIX/extensions/android-buildtools/share/java/d8.jar"

rm -rf "$BUILD/classes" "$BUILD/dex"
mkdir -p "$BUILD/classes" "$BUILD/dex"

echo "[1/6] 打包资源 (aapt)"
aapt package -f -M "$ANDROID/AndroidManifest.xml" -S "$ANDROID/res" -A "$ANDROID/assets" \
  -I /system/framework/framework-res.apk -F "$BUILD/app-unsigned.apk"

echo "[2/6] 编译 Java (javac)"
javac -source 8 -target 8 -encoding UTF-8 -bootclasspath "$AJAR" -d "$BUILD/classes" \
  "$ANDROID/src/com/jfbg/scoreboard/MainActivity.java" 2>&1 | grep -vE "^Note:|warning: \[options\]" || true

echo "[3/6] 转换 dex (d8)"
java -cp "$D8" com.android.tools.r8.D8 --release --min-api 24 --lib "$AJAR" \
  --output "$BUILD/dex" "$BUILD/classes/com/jfbg/scoreboard/"*.class

echo "[4/6] 合并 dex 到 APK"
cp "$BUILD/app-unsigned.apk" "$BUILD/app-with-dex.apk"
(cd "$BUILD/dex" && zip -q -X "$BUILD/app-with-dex.apk" classes.dex)

echo "[5/6] 对齐 (zipalign)"
zipalign -f 4 "$BUILD/app-with-dex.apk" "$BUILD/app-aligned.apk"

echo "[6/6] 签名 (apksigner)"
apksigner sign --ks "$BUILD/keystore.jks" --ks-key-alias scoreboard \
  --ks-pass pass:scoreboard2024 --key-pass pass:scoreboard2024 \
  --out "$BUILD/记分板-v1.0.0.apk" "$BUILD/app-aligned.apk"

apksigner verify "$BUILD/记分板-v1.0.0.apk" && echo "签名校验通过"
ls -lh "$BUILD/记分板-v1.0.0.apk"
