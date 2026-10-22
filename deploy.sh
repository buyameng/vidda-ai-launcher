#!/usr/bin/env bash
# ==============================================================================
# Vidda TV 启动器一键构建与部署脚本
# 用法: ./deploy.sh [TV_IP]
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ -z "${1:-}" ]; then
    echo "错误: 未指定电视 IP 地址" >&2
    echo "用法: ./deploy.sh <电视IP>" >&2
    echo "例如: ./deploy.sh 192.168.1.100" >&2
    exit 1
fi

TV_HOST="$1"
TV="${TV_HOST}:5555"

if [ -d "${SCRIPT_DIR}/launcher" ]; then
    REPO_DIR="${SCRIPT_DIR}/launcher"
else
    REPO_DIR="${SCRIPT_DIR}/repo/launcher"
fi

echo "=== 1. 配置环境 ==="
export JAVA_HOME="${JAVA_HOME:-}"
if [ -z "$JAVA_HOME" ] || [ ! -d "$JAVA_HOME" ]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17 2>/dev/null || echo "")"
fi
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="${JAVA_HOME}/bin:${PATH}"

echo "JAVA_HOME:    ${JAVA_HOME}"
echo "ANDROID_HOME: ${ANDROID_HOME}"
echo "目标设备:     ${TV}"

echo ""
echo "=== 2. 编译启动器 APK ==="
cd "${REPO_DIR}"
./gradlew assembleDebug -q --console=plain

APK_PATH="${REPO_DIR}/app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "${APK_PATH}" ]; then
    echo "错误: 未找到生成的 APK 文件: ${APK_PATH}" >&2
    exit 1
fi
echo "编译成功: ${APK_PATH} ($(du -h "${APK_PATH}" | awk '{print $1}'))"

echo ""
echo "=== 3. 连接电视 ADB ==="
RELAY_PID=""
cleanup_relay() {
    if [ -n "${RELAY_PID}" ]; then
        adb disconnect "127.0.0.1:15555" >/dev/null 2>&1 || true
        kill "${RELAY_PID}" >/dev/null 2>&1 || true
    fi
}
trap cleanup_relay EXIT

adb disconnect "${TV}" >/dev/null 2>&1 || true
if ! adb connect "${TV}" 2>&1 | grep -q "connected to"; then
    echo "直接连接受限 (macOS 局域网权限隔离)，启用本地 Python 端口转发至 ${TV_HOST}:5555..."
    python3 -c "
import socket, threading
def pipe(a, b):
    try:
        while True:
            d = a.recv(65536)
            if not d: break
            b.sendall(d)
    except Exception: pass
    finally:
        for s in (a, b):
            try: s.close()
            except Exception: pass
srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(('127.0.0.1', 15555))
srv.listen(16)
while True:
    try:
        c, _ = srv.accept()
        try:
            r = socket.create_connection(('${TV_HOST}', 5555), timeout=5)
            threading.Thread(target=pipe, args=(c, r), daemon=True).start()
            threading.Thread(target=pipe, args=(r, c), daemon=True).start()
        except Exception:
            try: c.close()
            except Exception: pass
    except Exception:
        break
" &
    RELAY_PID=$!
    sleep 1
    TV="127.0.0.1:15555"
    adb disconnect "${TV}" >/dev/null 2>&1 || true
    adb connect "${TV}"
fi

# 验证设备在线状态
STATE=$(adb -s "${TV}" get-state 2>/dev/null || echo "offline")
if [ "${STATE}" != "device" ]; then
    echo "错误: 电视当前状态为 [${STATE}]，请检查网络连接或在电视上确认 ADB 授权。" >&2
    exit 1
fi
echo "设备已就绪: ${TV}"

echo ""
echo "=== 4. 安装与配置权限 ==="
adb -s "${TV}" install -r -d "${APK_PATH}"
echo "授权位置与悬浮窗权限..."
adb -s "${TV}" shell pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
adb -s "${TV}" shell pm grant com.gathin.tvhome android.permission.ACCESS_COARSE_LOCATION 2>/dev/null || true
adb -s "${TV}" shell appops set com.gathin.tvhome COARSE_LOCATION allow 2>/dev/null || true
adb -s "${TV}" shell appops set com.gathin.tvhome FINE_LOCATION allow 2>/dev/null || true
adb -s "${TV}" shell pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true
adb -s "${TV}" shell pm grant com.gathin.tvhome android.permission.WRITE_SETTINGS 2>/dev/null || true
adb -s "${TV}" shell appops set com.gathin.tvhome SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
adb -s "${TV}" shell appops set --user 0 com.gathin.tvhome SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
adb -s "${TV}" shell appops set 10078 SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
adb -s "${TV}" shell appops set com.gathin.tvhome PROJECT_MEDIA allow 2>/dev/null || true

echo "配置本地局域网转发与端口反向通道..."
HOST_LAN_IP=$(python3 -c "
import socket
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
try:
    s.connect(('${TV_HOST}', 5555))
    print(s.getsockname()[0])
except:
    print('127.0.0.1')
finally:
    s.close()
")
echo "宿主机局域网 IP: ${HOST_LAN_IP}"

# 确保宿主机 3425 端口对局域网开放并转发至 127.0.0.1:3425
if ! nc -z "${HOST_LAN_IP}" 3425 2>/dev/null; then
    echo "启动宿主机局域网 3425 端口代理 -> 127.0.0.1:3425..."
    FORWARDER_SCRIPT="${SCRIPT_DIR}/tools/forwarder.py"
    if [ ! -f "${FORWARDER_SCRIPT}" ]; then
        FORWARDER_SCRIPT="${SCRIPT_DIR}/repo/tools/forwarder.py"
    fi
    if [ -f "${FORWARDER_SCRIPT}" ]; then
        nohup python3 "${FORWARDER_SCRIPT}" >/dev/null 2>&1 &
        sleep 0.5
    fi
fi

adb -s "${TV}" reverse tcp:3425 tcp:3425 2>/dev/null || true
adb -s "${TV}" shell settings put global tvhome_ai_url "http://${HOST_LAN_IP}:3425/v1" 2>/dev/null || true
adb -s "${TV}" shell settings put global tvhome_ai_fallback_url "http://127.0.0.1:3425/v1" 2>/dev/null || true
adb -s "${TV}" shell settings put global tvhome_weather_city_code "101120111" 2>/dev/null || true

echo "同步私有配置文件至电视设备存储..."
CFG_FILE=""
if [ -f "${SCRIPT_DIR}/tvhome_config.json" ]; then
    CFG_FILE="${SCRIPT_DIR}/tvhome_config.json"
elif [ -f "${REPO_DIR}/tvhome_config.json" ]; then
    CFG_FILE="${REPO_DIR}/tvhome_config.json"
fi

if [ -n "${CFG_FILE}" ]; then
    adb -s "${TV}" push "${CFG_FILE}" /sdcard/tvhome_config.json 2>/dev/null || true
    adb -s "${TV}" push "${CFG_FILE}" /data/local/tmp/tvhome_config.json 2>/dev/null || true
    python3 -c "
import json, subprocess
try:
    with open('${CFG_FILE}') as f:
        cfg = json.load(f)
    gw = cfg.get('gateway', {})
    key = gw.get('api_key', '').strip()
    model = gw.get('model', '').strip()
    if key and 'your-ai-gateway' not in key:
        subprocess.run(['adb', '-s', '${TV}', 'shell', 'settings', 'put', 'global', 'tvhome_ai_key', key], check=False)
    if model:
        subprocess.run(['adb', '-s', '${TV}', 'shell', 'settings', 'put', 'global', 'tvhome_ai_model', model], check=False)
    lt = cfg.get('light', {})
    base = lt.get('base_url', '').strip()
    if base:
        import urllib.parse
        host = urllib.parse.urlparse(base).hostname
        if host:
            subprocess.run(['adb', '-s', '${TV}', 'shell', 'settings', 'put', 'global', 'tvhome_ha_host', host], check=False)
except Exception:
    pass
" 2>/dev/null || true
fi

echo "关闭系统强制全双工常驻拾音（由 AiVoiceBridgeService 精准托管多轮拾音与退出）..."
adb -s "${TV}" shell am start -d "hispeech://aiarea/duplex?entertype=0\&column=personalization\&cardCode=FULL_DUPLEX\&switch=0" >/dev/null 2>&1 || true
sleep 1

echo "配置电视远场硬件自定义唤醒词（你好小智 / 海信小智 双唤醒引擎）..."
adb -s "${TV}" shell settings put global farfield_selfwakeup "你好小智" 2>/dev/null || true
adb -s "${TV}" shell settings put global wakeupState 1 2>/dev/null || true
adb -s "${TV}" shell settings put global double_wake_up_ability 1 2>/dev/null || true
adb -s "${TV}" shell settings put global customwakeup_user_confirm 1 2>/dev/null || true

adb -s "${TV}" shell settings put system farfield_selfwakeup "你好小智" 2>/dev/null || true
adb -s "${TV}" shell settings put system wakeupState 1 2>/dev/null || true
adb -s "${TV}" shell settings put system double_wake_up_ability 1 2>/dev/null || true
adb -s "${TV}" shell settings put system customwakeup_user_confirm 1 2>/dev/null || true

adb -s "${TV}" shell am broadcast -a com.speech.area.selfwakeup.add -p com.keylab.speech.core.vidaa --es name "你好小智" --ei source 0 2>/dev/null || true
adb -s "${TV}" shell am broadcast -a com.speech.area.selfwakeup.switch -p com.keylab.speech.core.vidaa --ez switch true --es name "你好小智" 2>/dev/null || true

echo "配置全局系统标准语音识别服务 (支持 YouTube / SmartTube 语音输入)..."
adb -s "${TV}" shell settings put secure voice_recognition_service com.gathin.tvhome/com.gathin.tvhome.speech.TvRecognitionService 2>/dev/null || true

echo "配置电视全局地区为美国并保障桌面网络标识读取..."
adb -s "${TV}" shell cmd location set-location-enabled true 2>/dev/null || true
adb -s "${TV}" shell settings put secure location_mode 3 2>/dev/null || true
adb -s "${TV}" shell settings delete global location_global_kill_switch 2>/dev/null || true
adb -s "${TV}" shell setprop persist.sys.country US 2>/dev/null || true
adb -s "${TV}" shell settings put global country US 2>/dev/null || true
adb -s "${TV}" shell settings put system country US 2>/dev/null || true
adb -s "${TV}" shell settings put global location "37.7749,-122.4194" 2>/dev/null || true
for pkg in org.smarttube.stable com.google.android.youtube.tv com.google.android.youtube.tvmusic; do
    adb -s "${TV}" shell cmd appops set $pkg COARSE_LOCATION deny 2>/dev/null || true
    adb -s "${TV}" shell cmd appops set $pkg FINE_LOCATION deny 2>/dev/null || true
done

echo ""
echo "=== 5. 设置为默认桌面与海信属性 ==="
adb -s "${TV}" shell setprop persist.vt.use_home_pkg com.gathin.tvhome
adb -s "${TV}" shell setprop sys.vt.start_u0_component com.gathin.tvhome/com.gathin.tvhome.MainActivity
adb -s "${TV}" shell cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity

echo ""
echo "=== 6. 配置 ClockDream 防烧屏时钟屏保 ==="
adb -s "${TV}" shell settings put secure screensaver_components com.gathin.tvhome/.ClockDream
adb -s "${TV}" shell settings put secure screensaver_enabled 1
adb -s "${TV}" shell settings put secure screensaver_activate_on_sleep 1

echo ""
echo "=== 7. 重启启动器生效 ==="
adb -s "${TV}" shell am force-stop com.gathin.tvhome 2>/dev/null || true
sleep 1
adb -s "${TV}" shell am start -n com.gathin.tvhome/.MainActivity
adb -s "${TV}" shell input keyevent KEYCODE_HOME
sleep 2

echo ""
echo "=== 8. 激活无障碍静默截屏服务 (V2 屏幕内容识别架构) ==="
ACC_SERVICE="com.gathin.tvhome/com.gathin.tvhome.speech.ScreenCaptureAccessibilityService"
BASE_SERVICE="com.hisense.vidaaassistant/com.hisense.vidaaassistant.settings.AppAccessibilityService"
# 先仅保留海信服务，安全触发系统清空 mCrashedServices 假死黑名单，绝不关闭 accessibility_enabled 总开关
adb -s "${TV}" shell settings put secure enabled_accessibility_services "${BASE_SERVICE}" 2>/dev/null || true
sleep 0.2
# 重新将 tvhome 截屏服务追加注册并激活
adb -s "${TV}" shell settings put secure enabled_accessibility_services "${BASE_SERVICE}:${ACC_SERVICE}" 2>/dev/null || true
adb -s "${TV}" shell settings put secure accessibility_enabled 1 2>/dev/null || true

echo ""
echo "=================================================="
echo " 部署完成！电视已切换至最新 tvhome 启动页。"
echo "=================================================="
