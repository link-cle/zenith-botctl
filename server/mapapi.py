#!/usr/bin/env python3
"""Map maintenance service for BotCtl (delete rendered map images).

Why a service: the phone app cannot delete files on the server, and ZenithProxy
has no command for it. The WebUI plugin can only serve static files, never
remove them.

Authentication reuses the panel session instead of inventing a new secret:
the app sends the same `webui_token` cookie it already holds, and we verify it
by calling the panel's own /api/auth/status on localhost. No credential is
stored here.

Endpoints
  GET  /api/health                 -> {"ok": true}
  POST /api/maps/delete  {"names": ["x.png", ...]} -> {"success":true,"deleted":n}
  POST /api/maps/clear                               -> {"success":true,"deleted":n}

Only plain *.png basenames directly inside /root/ZenithProxy/maps are touched.
"""
import json
import os
import re
import sys
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAPS_DIR = "/root/ZenithProxy/maps"
PANEL = "http://127.0.0.1:8080"
LISTEN = ("0.0.0.0", 8081)   # 8081 is already open in the cloud firewall
SAFE_NAME = re.compile(r"^[A-Za-z0-9_.\-]+\.png$")


def log(msg):
    print(msg, file=sys.stderr, flush=True)


def cookie_is_valid(cookie):
    """Ask the panel whether this session cookie is authenticated.

    NOTE: the panel binds a token to the IP it was issued to, so a cookie minted
    from the phone will *not* validate when we replay it from 127.0.0.1.
    Kept only as a fallback for same-host callers.
    """
    if not cookie:
        return False
    req = urllib.request.Request(PANEL + "/api/auth/status")
    req.add_header("Cookie", cookie)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            raw = r.read().decode("utf-8", "replace")
            return r.status == 200 and '"authenticated":true' in raw.replace(" ", "")
    except Exception:
        return False


def credentials_valid(user, pwd):
    """Verify panel credentials by logging in locally (IP-independent)."""
    if not user or not pwd:
        return False
    body = json.dumps({"username": user, "password": pwd}).encode()
    req = urllib.request.Request(PANEL + "/api/auth/login", data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=12) as r:
            raw = r.read().decode("utf-8", "replace")
            return r.status == 200 and '"success":true' in raw.replace(" ", "")
    except Exception:
        return False


def delete_maps(names):
    deleted = 0
    for name in names:
        if not isinstance(name, str) or not SAFE_NAME.match(name):
            continue
        path = os.path.join(MAPS_DIR, name)
        if os.path.dirname(os.path.abspath(path)) != os.path.abspath(MAPS_DIR):
            continue
        try:
            os.remove(path)
            deleted += 1
        except OSError:
            pass
    return deleted


def list_png():
    try:
        return [n for n in os.listdir(MAPS_DIR) if n.endswith(".png")]
    except OSError:
        return []


class Handler(BaseHTTPRequestHandler):
    server_version = "MapAdmin/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        log("%s %s" % (self.address_string(), fmt % args))

    def _send(self, code, obj):
        raw = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(raw)

    def _cookie(self):
        return self.headers.get("Cookie", "")

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "POST, GET, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Cookie")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        if self.path == "/api/health":
            self._send(200, {"ok": True, "service": "map-admin", "maps": len(list_png())})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
            payload = json.loads(self.rfile.read(length).decode("utf-8")) if length else {}
            if not isinstance(payload, dict):
                payload = {}
        except Exception:
            self._send(400, {"error": "请求格式不对"})
            return

        # auth: panel credentials (IP-independent) or a same-host session cookie
        if not (credentials_valid(str(payload.get("user", "")), str(payload.get("pass", "")))
                or cookie_is_valid(self._cookie())):
            self._send(401, {"error": "面板账号或密码不对（App 里填的面板凭据已失效？）"})
            return

        if self.path == "/api/maps/clear":
            names = list_png()
            self._send(200, {"success": True, "deleted": delete_maps(names)})
            return

        if self.path == "/api/maps/delete":
            names = payload.get("names", [])
            if not isinstance(names, list):
                self._send(400, {"error": "names 必须是数组"})
                return
            self._send(200, {"success": True, "deleted": delete_maps(names)})
            return

        self._send(404, {"error": "not found"})


if __name__ == "__main__":
    srv = ThreadingHTTPServer(LISTEN, Handler)
    log("map-admin service listening on %s:%d" % LISTEN)
    srv.serve_forever()
