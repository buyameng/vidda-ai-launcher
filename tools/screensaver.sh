#!/usr/bin/env bash
# Hisense / Vidda "艺术画廊" (Art Gallery) screensaver: diagnose, restore and test.
#
# The art gallery is not an AOSP Dream. It is driven by the Hisense framework off
# `screen_saver_mode` / `screensaver_start_settings` in Settings.System, and it only
# appears once the TV actually goes to sleep. Two things stop it on this box:
#   - `screensaver_components` was pointed at our own ClockDream, which the Hisense
#     framework ignores, so nothing is left to fall back to;
#   - `sleep_timeout` is 24h, so the TV never reaches sleep and nothing is ever shown.
#
# Usage:
#   tools/screensaver.sh status                    show every relevant key
#   tools/screensaver.sh fix                       restore art gallery (sleep_timeout untouched)
#   SLEEP_TIMEOUT_MIN=15 tools/screensaver.sh fix  ...and let the TV sleep after 15 min
#   tools/screensaver.sh test                      force the dream now, to see whether it works
#
# TV_HOST=192.168.1.50 tools/screensaver.sh status
set -uo pipefail

# A leading dotted IP is accepted as the TV address; everything else is the command.
if [ $# -gt 0 ] && [[ "${1}" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    TV_HOST="${1}"
    shift
fi
TV_HOST="${TV_HOST:-}"
if [ -z "${TV_HOST}" ]; then
    echo "usage: TV_HOST=<tv ip> $0 [ip] {status|fix|test}" >&2
    exit 1
fi
TV="${TV_HOST}:5555"
adb connect "${TV}" >/dev/null 2>&1 || true

tvsh() { adb -s "${TV}" shell "$@" 2>/dev/null; }

# Values captured in tv-backup/20261019-085711 (the TV's own art-gallery setup).
GALLERY_MODE="艺术画廊"
ART_DURATION=30

status() {
    echo "=== Screensaver keys ==="
    printf '%-38s %s\n' "secure.screensaver_components"        "$(tvsh settings get secure screensaver_components)"
    printf '%-38s %s\n' "secure.screensaver_default_component"  "$(tvsh settings get secure screensaver_default_component)"
    printf '%-38s %s\n' "secure.screensaver_enabled"            "$(tvsh settings get secure screensaver_enabled)"
    printf '%-38s %s\n' "secure.screensaver_activate_on_sleep"  "$(tvsh settings get secure screensaver_activate_on_sleep)"
    printf '%-38s %s\n' "secure.sleep_timeout"                  "$(tvsh settings get secure sleep_timeout)"
    printf '%-38s %s\n' "system.screen_saver_mode"              "$(tvsh settings get system screen_saver_mode)"
    printf '%-38s %s\n' "system.screensaver_start_settings"     "$(tvsh settings get system screensaver_start_settings)"
    printf '%-38s %s\n' "system.sp_key_art_play_duration"       "$(tvsh settings get system sp_key_art_play_duration)"
    printf '%-38s %s\n' "system.screen_off_timeout"             "$(tvsh settings get system screen_off_timeout)"
    printf '%-38s %s\n' "system.dim_screen"                     "$(tvsh settings get system dim_screen)"

    echo ""
    echo "=== Dream services the system can see ==="
    tvsh dumpsys package com.jamdeo.screensaver | grep -iE "DreamService|service|Activity" | head -20
    echo "--- basic dreams ---"
    tvsh dumpsys package com.android.dreams.basic | grep -iE "DreamService|android.service.dreams" | head -10
    echo "--- current dream state ---"
    tvsh dumpsys dream | head -20
    echo "--- is the screen on / who holds it awake ---"
    tvsh dumpsys power | grep -iE "mWakefulness|mScreenOn|Display Power|Wake Locks" | head -12
}

fix() {
    echo "=== 1. 停用 tvhome 的屏保劫持，交还给海信艺术画廊 ==="
    # The Hisense framework reads its own mode keys; screensaver_components only
    # matters to AOSP's DreamManager, so put back the stock component.
    tvsh settings put secure screensaver_components com.google.android.deskclock/com.android.deskclock.Screensaver
    tvsh settings put secure screensaver_default_component com.google.android.deskclock/com.android.deskclock.Screensaver
    tvsh settings put secure screensaver_enabled 1
    tvsh settings put secure screensaver_activate_on_sleep 1

    echo "=== 2. 恢复艺术画廊模式 ==="
    tvsh settings put system screen_saver_mode "${GALLERY_MODE}"
    tvsh settings put system screensaver_start_settings 2
    tvsh settings put system sp_key_art_play_duration "${ART_DURATION}"

    # sleep_timeout is a global power policy: it also decides when the far-field mic
    # and AiVoiceBridgeService go to sleep, so it is only touched on explicit request.
    if [ -n "${SLEEP_TIMEOUT_MIN:-}" ]; then
        local ms=$(( SLEEP_TIMEOUT_MIN * 60000 ))
        echo "=== 3. 设置全局休眠时间：${SLEEP_TIMEOUT_MIN} 分钟 ==="
        echo "    注意：这会让电视更早进入待机，语音唤醒会受影响。"
        tvsh settings put secure sleep_timeout "${ms}"
        echo "    sleep_timeout=${ms}ms"
    else
        echo "=== 3. sleep_timeout 保持不变 ==="
        echo "    当前值: $(tvsh settings get secure sleep_timeout)"
        echo "    电视不会因为空闲自动进入屏保。需要时："
        echo "      SLEEP_TIMEOUT_MIN=15 $0 $TV_HOST fix"
    fi

    echo "=== 4. 结果 ==="
    status
}

test_dream() {
    echo "=== 绕过空闲计时，直接让 DreamManager 起屏保 ==="
    adb -s "${TV}" shell service call dreams 1 >/dev/null 2>&1 || true
    sleep 2
    echo ""
    echo "=== 当前屏保状态 ==="
    tvsh dumpsys dreams | grep -iE "mCurrentDream|mBound" | head -8
    echo ""
    echo "=== 当前前台窗口 ==="
    tvsh dumpsys window | grep -iE "mCurrentFocus|mFocusedApp" | head -4
}

case "${1:-status}" in
    status) status ;;
    fix)    fix ;;
    test)   test_dream ;;
    *)      echo "usage: TV_HOST=<tv ip> $0 [ip] {status|fix|test}" >&2; exit 1 ;;
esac
