#!/usr/bin/env bash
set -euo pipefail

# PhotoPilot Android E2E 环境一键脚本（macOS / bash 3.2）
# 子命令见 usage()。UI 交互驱动（uiautomator 坐标点按）不固化，坐标随布局变化易碎，
# 由人工或 AI 按下述 16 步清单驱动，每步先 dump 解析坐标再点按，禁止盲点：
#   a  冷启动 → S02 欢迎页（截图 01）
#   b  Choose photos → S04 来源页（02）
#   c  Photos on this device → S05 权限页（03）
#   d  Continue → S08 导入进度，轮询至 Import complete（04/05）
#   e  Choose what to display → S10 选片，点选 3 张保存（06/07）
#   f  首启自动进 S13 播放设置 → 保存（08）
#   g  S03 相框卡 → Start slideshow → 播放器（09/10），点屏中央出控件条（11），退出
#   h  Library 主区网格（12）→ 点照片进详情（13）→ 返回
#   i  Sources（14）/ Settings（15）主区
#   j  am force-stop 后冷启动 → 自动续播播放器（16），退出
#   补充：播放 10s 转场推进（17）、横屏 sidebar（18）、竖屏 rail（18b）、深色（19）
# 每步截图用：tools/emu-e2e.sh shots <名称>；结束检查 logcat 有无 FATAL EXCEPTION。

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${JAVA_HOME:=/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
export JAVA_HOME ANDROID_HOME
ADB="$ANDROID_HOME/platform-tools/adb"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
EMULATOR_BIN="$ANDROID_HOME/emulator/emulator"
PKG="dev.photopilot.scaffold"
AVD_NAME="pp_m1_test"
SYSIMG="system-images;android-36;google_apis;arm64-v8a"
SEED_DIR="/sdcard/Pictures/PhotoPilotTest"
SHOTS_DIR="/tmp/pp-emu-shots"
EMU_LOG="/tmp/pp_emulator.log"

usage() {
  sed -n '4,18p' "$0"
  cat <<'EOF'

用法: tools/emu-e2e.sh <子命令>
  setup      安装 emulator 与 system-image（如缺）、创建 Pixel Tablet AVD(pp_m1_test)、headless 启动并等待开机
  seed       构建 debug APK、安装授权、推送 22 张夹具照片并媒体扫描、验证 MediaStore 入库数
  shots <名> 截图到 /tmp/pp-emu-shots/<名>.png
  reset      pm clear 应用数据（保留 MediaStore 照片）
  teardown   关模拟器、删 AVD、卸载 emulator 与 system-image、清 /tmp 插桩产物（保留截图目录）
EOF
}

need_device() {
  "$ADB" get-state 1 >/dev/null 2>&1 || { echo "错误：无已连接设备（先跑 setup）" >&2; exit 1; }
}

wait_boot() {
  local i s
  for i in $(seq 1 60); do
    s="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    [ "$s" = "1" ] && { echo "boot_completed（约 $((i*5))s）"; return 0; }
    sleep 5
  done
  echo "错误：等待开机超时，诊断见 $EMU_LOG" >&2
  return 1
}

cmd_setup() {
  [ -x "$SDKMANAGER" ] || { echo "错误：未找到 sdkmanager（$SDKMANAGER），请先安装 cmdline-tools" >&2; exit 1; }
  if [ ! -x "$EMULATOR_BIN" ] || [ ! -d "$ANDROID_HOME/system-images/android-36/google_apis/arm64-v8a" ]; then
    yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
    "$SDKMANAGER" "emulator" "$SYSIMG"
  fi
  if [ ! -d "$HOME/.android/avd/$AVD_NAME.avd" ]; then
    echo no | "$AVDMANAGER" create avd -n "$AVD_NAME" -k "$SYSIMG" -d pixel_tablet --force
  fi
  if "$ADB" devices 2>/dev/null | grep -q "emulator-"; then
    echo "模拟器已在运行"
  else
    nohup "$EMULATOR_BIN" -avd "$AVD_NAME" -no-window -no-audio -no-snapshot-save >"$EMU_LOG" 2>&1 &
    echo "模拟器启动中（pid $!），日志 $EMU_LOG"
  fi
  wait_boot
}

cmd_seed() {
  need_device
  (cd "$ROOT" && make android-build)
  "$ADB" install -r -g "$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
  "$ADB" shell pm grant "$PKG" android.permission.READ_MEDIA_IMAGES
  "$ADB" shell mkdir -p "$SEED_DIR"
  local staged="/tmp/pp-emu-photos"
  rm -rf "$staged"; mkdir -p "$staged"
  ls "$ROOT/tests/fixtures/smoke-v1/F26/"*.png | sort | head -20 | while IFS= read -r f; do cp "$f" "$staged/"; done
  cp "$ROOT/tests/fixtures/smoke-v1/F11/no-date.jpg" "$ROOT/tests/fixtures/smoke-v1/F17/alpha.png" "$staged/"
  "$ADB" push "$staged/." "$SEED_DIR/" >/dev/null
  local f n
  for f in "$staged"/*; do
    n="$(basename "$f")"
    "$ADB" shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$SEED_DIR/$n" >/dev/null
  done
  sleep 3
  local count
  count="$("$ADB" shell "content query --uri content://media/external/images/media --projection _id --where \"_data LIKE '%PhotoPilotTest%'\"" | grep -c "Row:" || true)"
  echo "MediaStore 入库: $count/22"
  [ "$count" = "22" ] || { echo "错误：入库数不等于 22" >&2; exit 1; }
}

cmd_shots() {
  need_device
  [ $# -ge 1 ] || { echo "错误：shots 需要一个名称参数" >&2; exit 1; }
  mkdir -p "$SHOTS_DIR"
  local name="${1%.png}"
  "$ADB" exec-out screencap -p > "$SHOTS_DIR/$name.png"
  echo "$SHOTS_DIR/$name.png"
}

cmd_reset() {
  need_device
  "$ADB" shell pm clear "$PKG"
}

cmd_teardown() {
  "$ADB" emu kill 2>/dev/null || true
  sleep 2
  [ -x "$AVDMANAGER" ] && "$AVDMANAGER" delete avd -n "$AVD_NAME" || true
  if [ -x "$SDKMANAGER" ]; then
    "$SDKMANAGER" --uninstall "$SYSIMG" "emulator" || true
  fi
  rm -rf /tmp/pp-emu-build /tmp/pp-emu-db /tmp/pp-emu-photos /tmp/pp-emu-mediaquery.txt "$EMU_LOG"
  rm -f /tmp/pp-emu-ui*.xml /tmp/pp-emu-dump.py
  echo "teardown 完成（截图目录 $SHOTS_DIR 已保留）"
}

case "${1:-}" in
  setup) cmd_setup ;;
  seed) cmd_seed ;;
  shots) shift; cmd_shots "$@" ;;
  reset) cmd_reset ;;
  teardown) cmd_teardown ;;
  *) usage ;;
esac
