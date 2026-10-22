#!/usr/bin/env python3
"""Local AI Gateway LAN Port Forwarder.
Binds to 0.0.0.0 or LAN IP on port 3425 and forwards to 127.0.0.1:3425 (Magpie).
"""
import socket
import threading
import sys
import os

def forward(src, dst):
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
    except Exception:
        pass
    finally:
        try: src.close()
        except Exception: pass
        try: dst.close()
        except Exception: pass

def get_lan_ips():
    """获取所有有效的物理局域网 IP（排除 127.* 回环与 198.18.* / 198.19.* 等 TUN/VPN 虚拟网卡）"""
    ips = []
    for test_dest in [("192.168.5.26", 5555), ("192.168.1.1", 80), ("10.0.0.1", 80)]:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(test_dest)
            ip = s.getsockname()[0]
            s.close()
            if ip and not ip.startswith("127.") and not ip.startswith("198.18.") and not ip.startswith("198.19."):
                if ip not in ips:
                    ips.append(ip)
        except Exception:
            pass
    return ips

def main():
    port = int(os.environ.get("GATEWAY_PORT", "3425"))
    target_host = os.environ.get("GATEWAY_UPSTREAM_HOST", "127.0.0.1")
    target_port = int(os.environ.get("GATEWAY_UPSTREAM_PORT", str(port)))

    # 优先使用 0.0.0.0 监听全网卡，确保局域网设备与本机均可直达
    bind_hosts = ["0.0.0.0"]
    for ip in get_lan_ips():
        if ip not in bind_hosts:
            bind_hosts.append(ip)

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    if hasattr(socket, "SO_REUSEPORT"):
        try:
            srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
        except Exception:
            pass

    bound = False
    for host in bind_hosts:
        try:
            srv.bind((host, port))
            print(f"[Forwarder] Listening on {host}:{port} -> {target_host}:{target_port}")
            sys.stdout.flush()
            bound = True
            break
        except Exception as e:
            print(f"[Forwarder] Cannot bind {host}:{port}: {e}")
            sys.stdout.flush()

    if not bound:
        print(f"[Forwarder] Failed to bind any host for port {port}", file=sys.stderr)
        sys.exit(1)

    srv.listen(128)
    while True:
        try:
            client, addr = srv.accept()
            try:
                upstream = socket.create_connection((target_host, target_port), timeout=10)
                threading.Thread(target=forward, args=(client, upstream), daemon=True).start()
                threading.Thread(target=forward, args=(upstream, client), daemon=True).start()
            except Exception as e:
                try: client.close()
                except Exception: pass
        except Exception:
            pass

if __name__ == "__main__":
    main()

