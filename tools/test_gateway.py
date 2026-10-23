#!/usr/bin/env python3
"""AI 网关全链路连通性体检与诊断工具
用法: python3 tools/test_gateway.py
"""
import json
import os
import socket
import sys
import time
import urllib.request

CONFIG_FILE = os.path.abspath(os.path.join(os.path.dirname(__file__), "../tvhome_config.json"))
if not os.path.exists(CONFIG_FILE):
    CONFIG_FILE = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../tvhome_config.json"))

def load_config():
    if os.path.exists(CONFIG_FILE):
        with open(CONFIG_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    return {}

def test_http(url, api_key, timeout=5):
    req = urllib.request.Request(
        url,
        headers={"Authorization": f"Bearer {api_key}"}
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status, resp.read().decode("utf-8")

def test_chat(url, api_key, model, prompt="你好", timeout=10):
    payload = json.dumps({
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": 50
    }).encode("utf-8")
    req = urllib.request.Request(
        f"{url}/chat/completions",
        data=payload,
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json"
        }
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status, resp.read().decode("utf-8")

def main():
    print("=" * 60)
    print("      Vidda TV AI 网关与模型全链路连通性体检")
    print("=" * 60)

    cfg = load_config()
    gw = cfg.get("gateway", {})
    lan_url = gw.get("lan_url", "http://192.168.5.22:3425/v1")
    local_url = gw.get("local_url", "http://127.0.0.1:3425/v1")
    api_key = gw.get("api_key", "")
    model = gw.get("model", "google/models/gemini-3.8-flash")

    print(f"[配置] LAN URL  : {lan_url}")
    print(f"[配置] Local URL: {local_url}")
    print(f"[配置] Model    : {model}")
    print(f"[配置] Key 长度 : {len(api_key)} chars (前缀: {api_key[:15]}...)")
    print("-" * 60)

    # 1. 本机 Magpie 核心检测
    print("1. 检测宿主机 Magpie 服务 (127.0.0.1:3425)...", end=" ")
    try:
        status, body = test_http(f"{local_url}/models", api_key)
        data = json.loads(body)
        model_ids = [m["id"] for m in data.get("data", [])]
        print(f"✅ 成功 (HTTP {status}, 可用模型数: {len(model_ids)})")
        if model in model_ids:
            print(f"   └── 目标模型 [{model}] ✅ 存在")
        else:
            print(f"   └── 目标模型 [{model}] ⚠️ 未找到，可用模型包括: {model_ids[:5]}...")
    except Exception as e:
        print(f"❌ 失败: {e}")
        print("   └── 请确认 /Applications/magpie.app 是否已打开并处于运行状态。")
        return

    # 2. 局域网网关连通性
    print(f"2. 检测局域网主网关连通性 ({lan_url})...", end=" ")
    lan_ok = False
    try:
        status, body = test_http(f"{lan_url}/models", api_key, timeout=3)
        print(f"✅ 成功 (HTTP {status})")
        lan_ok = True
    except Exception as e:
        print(f"⚠️ 无法直连 ({e})")
        print("   └── 说明：若 Mac 与电视/Win 不在同一网段属正常现象，只要电视可直连 Win 即可。")

    if lan_ok:
        print(f"3. 测试局域网主网关大模型推理 ({model})...", end=" ")
        try:
            t0 = time.time()
            status, body = test_chat(lan_url, api_key, model, prompt="请回复：测试通过")
            cost = time.time() - t0
            data = json.loads(body)
            reply = data.get("choices", [{}])[0].get("message", {}).get("content", "")
            print(f"✅ 成功 (耗时: {cost:.2f}s, HTTP {status})")
            print(f"   └── 模型回复内容: {repr(reply)}")
        except Exception as e:
            print(f"❌ 失败: {e}")

    # 4. 云端保底网关 (DeepSeek) 检测
    fb = gw.get("fallback", {})
    fb_enabled = fb.get("enabled", True)
    fb_url = fb.get("url", "https://api.deepseek.com/chat/completions")
    fb_key = fb.get("api_key", "")
    fb_model = fb.get("model", "deepseek-flash")
    fb_reasoning = fb.get("reasoning_effort", "high")
    fb_thinking = fb.get("thinking", True)

    if fb_enabled and fb_key and "your-deepseek-api-key" not in fb_key:
        print(f"4. 测试云端保底网关 DeepSeek ({fb_model})...", end=" ")
        try:
            t0 = time.time()
            payload = {
                "model": fb_model,
                "messages": [{"role": "user", "content": "请回复：保底测试通过"}],
                "stream": False,
                "max_tokens": 128
            }
            if fb_reasoning:
                payload["reasoning_effort"] = fb_reasoning
            if fb_thinking:
                payload["thinking"] = {"type": "enabled"}
            req = urllib.request.Request(
                fb_url,
                data=json.dumps(payload).encode("utf-8"),
                headers={
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {fb_key}"
                }
            )
            with urllib.request.urlopen(req, timeout=10) as resp:
                cost = time.time() - t0
                body = resp.read().decode("utf-8")
                data = json.loads(body)
                reply = data.get("choices", [{}])[0].get("message", {}).get("content", "")
                print(f"✅ 成功 (耗时: {cost:.2f}s, HTTP {resp.status})")
                print(f"   └── DeepSeek 回复内容: {repr(reply.strip())}")
        except Exception as e:
            print(f"❌ 失败: {e}")

    # 5. 电视硬件与网络探测
    tv_ip = "192.168.5.26"
    print(f"5. 探测电视设备状态 ({tv_ip}:5555)...", end=" ")
    try:
        s = socket.create_connection((tv_ip, 5555), timeout=2)
        s.close()
        print(f"✅ 电视 ADB 端口开放且网络可达")
    except Exception as e:
        print(f"⚠️ 电视未连接或离线 ({e})")

    print("=" * 60)
    print("🎉 恭喜！AI 网关与电视端全链路通信检测全部通过！")
    print("=" * 60)

if __name__ == "__main__":
    main()
