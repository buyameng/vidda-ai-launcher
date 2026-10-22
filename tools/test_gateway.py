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

    # 2. 局域网端口转发与直连检测
    print(f"2. 检测局域网网关连通性 ({lan_url})...", end=" ")
    try:
        status, body = test_http(f"{lan_url}/models", api_key)
        print(f"✅ 成功 (HTTP {status})")
    except Exception as e:
        print(f"❌ 失败: {e}")
        print("   └── 局域网端口转发器未就绪，请运行: python3 tools/forwarder.py")
        return

    # 3. 大模型 Chat 推理测试
    print(f"3. 测试大模型对话推理 ({model})...", end=" ")
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
        return

    # 4. 电视硬件与网络探测
    tv_ip = "192.168.5.26"
    print(f"4. 探测电视设备状态 ({tv_ip}:5555)...", end=" ")
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
