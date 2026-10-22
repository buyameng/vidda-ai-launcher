#!/usr/bin/env python3
"""Local AI Gateway LAN Port Forwarder.
Binds to LAN IP or all interfaces on port 3425 and forwards to 127.0.0.1:3425 (Magpie).
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

def main():
    port = int(os.environ.get("GATEWAY_PORT", "3425"))
    # 尝试绑定 LAN IP 或 0.0.0.0
    bind_hosts = ["0.0.0.0"]
    # 获取本地局域网 IP
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        lan_ip = s.getsockname()[0]
        s.close()
        if lan_ip and lan_ip not in bind_hosts:
            bind_hosts.insert(0, lan_ip)
    except Exception:
        pass

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

    bound = False
    for host in bind_hosts:
        try:
            srv.bind((host, port))
            print(f"[Forwarder] Listening on {host}:{port} -> 127.0.0.1:{port}")
            sys.stdout.flush()
            bound = True
            break
        except Exception as e:
            print(f"[Forwarder] Cannot bind {host}:{port}: {e}")
            sys.stdout.flush()

    if not bound:
        sys.exit(1)

    srv.listen(128)
    while True:
        try:
            client, addr = srv.accept()
            upstream = socket.create_connection(("127.0.0.1", port), timeout=10)
            threading.Thread(target=forward, args=(client, upstream), daemon=True).start()
            threading.Thread(target=forward, args=(upstream, client), daemon=True).start()
        except Exception as e:
            pass

if __name__ == "__main__":
    main()
