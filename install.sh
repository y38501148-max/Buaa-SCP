#!/usr/bin/env bash
#
# 通过 USB 安装并启动北航校园卡便携客户端（Buaa-SCP）。
#
# 用法：
#   ./install.sh                 # 自动找唯一在线设备，优先安装 dist 中的 release APK
#   ./install.sh path/to/x.apk   # 指定 APK
#   ADB=/path/to/adb ./install.sh
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ADB="${ADB:-adb}"
APK_DEFAULT="$(ls -1 "$HERE"/dist/BuaaSCP-*-release.apk 2>/dev/null | sort -V | tail -1 || true)"
if [[ -z "$APK_DEFAULT" ]]; then
  APK_DEFAULT="$(ls -1 "$HERE"/dist/BuaaSCP-*.apk 2>/dev/null | sort -V | tail -1 || true)"
fi
APK="${1:-$APK_DEFAULT}"
PKG="cn.edu.buaa.scp"
ACTIVITY="cn.edu.buaa.scp/.ui.LoginActivity"

if ! command -v "$ADB" >/dev/null 2>&1; then
  cat >&2 <<'EOF'
未找到 adb。请先安装 Android platform-tools，或指定路径：
  ADB=$HOME/Library/Android/sdk/platform-tools/adb ./install.sh
  brew install --cask android-platform-tools
EOF
  exit 1
fi

if [[ -z "$APK" || ! -f "$APK" ]]; then
  echo "未找到 APK：${APK:-<dist/ 下没有 BuaaSCP-*.apk>}" >&2
  echo "先构建：./gradlew :app:assembleRelease   （或 :app:assembleDebug）" >&2
  exit 1
fi

DEVICES="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')"
COUNT="$(printf '%s\n' "$DEVICES" | sed '/^$/d' | wc -l | tr -d ' ')"

if [[ "$COUNT" -eq 0 ]]; then
  cat >&2 <<'EOF'
没有检测到已授权的 USB 设备。请检查：
  1) 手机已开启「开发者选项 → USB 调试」；
  2) USB 线连接后，手机上点「允许 USB 调试」（可勾选始终允许）；
  3) `adb devices` 应该显示 "<序列号>  device"（显示 unauthorized 需先在手机上授权）。
EOF
  "$ADB" devices >&2
  exit 1
fi

if [[ "$COUNT" -gt 1 ]]; then
  echo "检测到多台设备，默认安装到第一台：$(printf '%s\n' "$DEVICES" | head -1)"
  SERIAL="$(printf '%s\n' "$DEVICES" | head -1)"
  ADB_ARGS=(-s "$SERIAL")
else
  ADB_ARGS=()
fi

echo "==> APK: $APK"
echo "==> 安装中…"
if ! "$ADB" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} install -r "$APK"; then
  cat >&2 <<'EOF'
安装失败。若提示 INSTALL_FAILED_UPDATE_INCOMPATIBLE（签名不一致），说明手机上装的是
另一套签名的旧版本。先卸载再装（会清除本机会话，需要重新登录）：

  adb uninstall cn.edu.buaa.scp
  ./install.sh

若提示 User rejected permissions，请在手机上点「允许」/「授权本次安装」后重试。
EOF
  exit 1
fi

echo "==> 启动 App"
"$ADB" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell am force-stop "$PKG" || true
"$ADB" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell am start -n "$ACTIVITY" >/dev/null

VERSION="$("$ADB" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell dumpsys package "$PKG" 2>/dev/null \
  | awk -F= '/versionName=/ {print $2; exit}' | tr -d '[:space:]')"
echo "==> 完成：$PKG ${VERSION:-（版本未知）}"
cat <<'EOF'

提示：
- 首次启动可先点「网络自检」，确认设备到学校服务的链路正常；
- 登录需要统一身份认证账号密码；若页面出现验证码，直接输入图片中的字符，点击图片可换一张；
- 首页余额卡「去充值」与卡务页「校园卡充值」会打开学校官方充值页，支付由校方平台完成；
- 运行日志默认隐藏：设置 → 连点「版本」7 次。
EOF
