#!/usr/bin/env bash
# Local debug workflow; all device operations target the named emulator only.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
AVD="${ANDROID_DEV_AVD:-SniffSub_API_36}"
PACKAGE=com.sniffsub.sniff_sub_player
ACTIVITY="$PACKAGE.MainActivity"
OUTPUT_DIR="$PROJECT_DIR/artifacts/local-debug"
APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"

fail() { echo "$*" >&2; exit 1; }
cli() { android --no-metrics --sdk="$ANDROID_HOME" "$@"; }
serial_for_avd() {
    local candidate name
    while read -r candidate; do
        name="$(adb -s "$candidate" emu avd name 2>/dev/null | tr -d '\r' | head -n 1)"
        if [[ "$name" == "$AVD" ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done < <(adb devices | awk '$1 ~ /^emulator-/ && $2 == "device" {print $1}')
    return 1
}
require_serial() {
    SERIAL="$(serial_for_avd)" || fail "模拟器 $AVD 尚未启动。先运行 android-dev start。"
    [[ "$(adb -s "$SERIAL" shell getprop sys.boot_completed | tr -d '\r')" == 1 ]] || fail "模拟器仍在启动，请稍后重试。"
}
start() {
    if ! serial_for_avd >/dev/null; then
        cli emulator start "$AVD" "$@"
    fi
    require_serial
    echo "模拟器已就绪：$AVD ($SERIAL)"
}
build() {
    if [[ -n "${ANDROID_DEV_JAVA_HOME:-}" ]]; then
        export JAVA_HOME="$ANDROID_DEV_JAVA_HOME"
    else
        JAVA_HOME="$(/usr/libexec/java_home -v 22 2>/dev/null || /usr/libexec/java_home -v 17)"
        export JAVA_HOME
    fi
    (cd "$PROJECT_DIR" && ./gradlew :app:assembleDebug --console=plain --no-daemon "$@")
}

command="${1:-help}"
if [[ $# -gt 0 ]]; then shift; fi
case "$command" in
    doctor)
        echo "项目：$PROJECT_DIR"
        echo "SDK：$ANDROID_HOME"
        echo "AVD：$AVD"
        /usr/libexec/java_home -V
        cli info
        cli emulator list
        "$ANDROID_HOME/emulator/emulator" -accel-check
        adb devices -l
        ;;
    start) start "$@" ;;
    build) build "$@" ;;
    run)
        [[ $# == 0 ]] || fail "用法：android-dev run（构建参数请使用 android-dev build）"
        start
        build
        cli run --device="$SERIAL" --apks="$APK" --activity="$ACTIVITY"
        ;;
    install)
        [[ $# -le 1 ]] || fail "用法：android-dev install [APK路径]"
        target_apk="${1:-$APK}"
        [[ -f "$target_apk" ]] || fail "APK 不存在：$target_apk"
        start
        cli install --device="$SERIAL" --apks="$target_apk"
        ;;
    launch)
        require_serial
        adb -s "$SERIAL" shell am start -W -n "$PACKAGE/$ACTIVITY"
        ;;
    screenshot)
        require_serial
        mkdir -p "$OUTPUT_DIR"
        cli screen capture --device="$SERIAL" --output="$OUTPUT_DIR/screen-$(date +%Y%m%d-%H%M%S).png" "$@"
        ;;
    layout)
        require_serial
        mkdir -p "$OUTPUT_DIR"
        cli layout --device="$SERIAL" --pretty --output="$OUTPUT_DIR/layout-$(date +%Y%m%d-%H%M%S).json" "$@"
        ;;
    logs)
        require_serial
        app_pid="$(adb -s "$SERIAL" shell pidof -s "$PACKAGE" | tr -d '\r')" || true
        [[ -n "$app_pid" ]] || fail "应用未运行，先运行 android-dev launch。"
        adb -s "$SERIAL" logcat --pid="$app_pid" "$@"
        ;;
    crash)
        require_serial
        adb -s "$SERIAL" logcat -b crash -d "$@"
        ;;
    adb)
        require_serial
        adb -s "$SERIAL" "$@"
        ;;
    stop)
        require_serial
        cli emulator stop "$SERIAL"
        ;;
    help|-h|--help)
        cat <<'HELP'
android-dev doctor                 检查 SDK、Java、模拟器和硬件加速
android-dev start [--headless]      启动模拟器；默认显示窗口
android-dev run                     构建 Debug APK、安装并启动应用
android-dev build [Gradle参数]      只构建 Debug APK
android-dev install [APK路径]       安装现有 APK（不构建、不启动应用）
android-dev launch                  启动已安装的 SniffSubPlayer
android-dev screenshot [--annotate] 保存截图；可标注可操作区域
android-dev layout                  保存当前 UI 布局树 JSON
android-dev logs [-d]               当前应用进程日志；Ctrl-C 停止
android-dev crash                   输出模拟器崩溃日志
android-dev adb <参数>              对此模拟器执行 adb 命令
android-dev stop                    关闭模拟器

截图、布局输出到项目 artifacts/local-debug/（已忽略）。
ANDROID_DEV_AVD 可选择另一个已创建的模拟器；不会自动选择物理设备。
HELP
        ;;
    *) fail "未知命令：$command。运行 android-dev help 查看用法。" ;;
esac
