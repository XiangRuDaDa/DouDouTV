#!/usr/bin/env bash
# DouDouTv 一键构建可安装 APK
# 用法：bash build.sh
# 依赖：JDK 17 或 21、Android SDK（compileSdk 34）、Gradle 8.7

set -euo pipefail

echo "==> 1/4 检查 Java"
if ! command -v java >/dev/null 2>&1; then
  echo "错误：未找到 java，请安装 JDK 17 或 21" >&2
  exit 1
fi
JAVA_MAJOR=$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')
echo "    Java major = $JAVA_MAJOR"
if [ "$JAVA_MAJOR" -lt 17 ]; then
  echo "错误：需要 JDK 17 及以上（当前 $JAVA_MAJOR）" >&2
  exit 1
fi

echo "==> 2/4 检查 Android SDK"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
if [ ! -d "$SDK" ]; then
  echo "错误：未找到 Android SDK。请设置 ANDROID_HOME，例如：" >&2
  echo "  export ANDROID_HOME=\$HOME/Android/Sdk" >&2
  exit 1
fi
echo "    SDK = $SDK"
echo "sdk.dir=$SDK" > local.properties

echo "==> 3/4 检查 SDK 组件"
MISSING=""
[ -d "$SDK/platforms/android-34" ]     || MISSING="$MISSING platforms;android-34"
[ -d "$SDK/build-tools/34.0.0" ]       || MISSING="$MISSING build-tools;34.0.0"
if [ -n "$MISSING" ]; then
  SDKMGR="$SDK/cmdline-tools/latest/bin/sdkmanager"
  if [ -x "$SDKMGR" ]; then
    echo "    缺少组件，正在安装：$MISSING"
    yes | "$SDKMGR" --install $MISSING
  else
    echo "错误：缺少组件且未找到 sdkmanager，请在 Android Studio 的 SDK Manager 中安装：" >&2
    echo "  Android 14 (API 34) Platform + Build-Tools 34.0.0" >&2
    exit 1
  fi
fi

echo "==> 4/4 构建 Debug APK"
if [ -x ./gradlew ]; then
  ./gradlew assembleDebug --stacktrace
else
  echo "    未找到 gradlew，使用系统 gradle 生成 wrapper"
  gradle wrapper --gradle-version 8.7
  ./gradlew assembleDebug --stacktrace
fi

APK="app/build/outputs/apk/debug/app-debug.apk"
echo
if [ -f "$APK" ]; then
  echo "✅ 构建成功"
  ls -lh "$APK"
  echo
  echo "安装到已连接设备："
  echo "  adb install -r $APK"
else
  echo "❌ 未生成 APK，请查看上方日志" >&2
  exit 1
fi
