#!/bin/bash
# BatteryHub 手動ビルドスクリプト（aapt2 -> javac -> d8 -> zipalign -> apksigner）
# 通常の開発環境では `gradle assembleDebug` でもビルドできます。
# このVMでは Gradle デーモン通信（JavaのTCP）が権限設定で遮断されるため、
# 公式 build-tools のチェーンで等価なデバッグAPKを生成します。
set -e
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk17}"
export PATH="$JAVA_HOME/bin:$PATH"
BT="${ANDROID_BUILD_TOOLS:-$HOME/tools/android-sdk/build-tools/34.0.0}"
AJAR="${ANDROID_JAR:-$HOME/tools/android-sdk/platforms/android-34/android.jar}"
KS="${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"

rm -rf build-manual
mkdir -p build-manual/gen build-manual/classes build-manual/dex

"$BT/aapt2" compile --dir app/src/main/res -o build-manual/res.zip
"$BT/aapt2" link -o build-manual/app-unsigned.apk -I "$AJAR" \
    --manifest app/src/main/AndroidManifest.xml \
    --java build-manual/gen \
    --min-sdk-version 26 --target-sdk-version 34 \
    --version-code 5 --version-name 1.4 \
    build-manual/res.zip

find app/src/main/java build-manual/gen -name "*.java" > build-manual/sources.txt
javac -encoding UTF-8 -source 17 -target 17 -cp "$AJAR" \
    -d build-manual/classes @build-manual/sources.txt

jar --create --file build-manual/classes.jar -C build-manual/classes .
"$BT/d8" --min-api 26 --lib "$AJAR" --output build-manual/dex build-manual/classes.jar

cp build-manual/app-unsigned.apk build-manual/app-with-dex.apk
(cd build-manual/dex && zip -q ../app-with-dex.apk classes.dex)
"$BT/zipalign" -p -f 4 build-manual/app-with-dex.apk build-manual/app-aligned.apk

if [ ! -f "$KS" ]; then
    mkdir -p "$(dirname "$KS")"
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10957 \
        -dname "CN=Android Debug,O=Android,C=US"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out BatteryHub-debug.apk build-manual/app-aligned.apk

echo "Built: $(pwd)/BatteryHub-debug.apk"
"$BT/apksigner" verify --verbose BatteryHub-debug.apk | head -3
