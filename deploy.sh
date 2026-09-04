#!/bin/bash
set -e

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "=========================================="
echo "   LSPosed 模块真机部署与实时调试脚本   "
echo "=========================================="

echo "[1/4] 正在检测已连接的 ADB 真机设备..."
DEVICES=$(adb devices | grep -v "List of devices" | grep "device$" | awk '{print $1}')
if [ -z "$DEVICES" ]; then
    echo "[x] 错误：未检测到已授权的 ADB 真机设备！"
    echo "    请确保："
    echo "    1. 手机已通过数据线或网络 ADB 连接至 Mac"
    echo "    2. 手机已开启【开发者选项】并启用【USB 调试】"
    echo "    3. 手机屏幕弹出授权提示时勾选【始终允许此计算机调试】"
    exit 1
fi
echo "[+] 成功连接真机设备: $DEVICES"

echo "[2/4] 正在编译 Debug APK (assembleDebug)..."
./gradlew assembleDebug

APK_PATH="$DIR/app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK_PATH" ]; then
    echo "[x] 错误：未找到生成的 APK 文件: $APK_PATH"
    exit 1
fi

echo "[3/4] 正在安装 APK 到真机..."
adb install -r "$APK_PATH"
echo "[+] 安装成功！"
echo "[!] 提示：如果是首次安装，请在手机端【LSPosed 管理器】中启用此模块并勾选目标作用域。"

echo "[4/4] 启动 LSPosed / 模块实时日志过滤 (按 Ctrl+C 退出)..."
adb logcat -s "LSPosed" "Xposed" "LSPosedFramework" "LSPosedModule" "LSPosed-Rotation" "LSPosed-RotationGesture" "LSPosed-3FingerSwipe" "LSPosed-3FingerTripleTap" "LSPosed-RotationConfig"

