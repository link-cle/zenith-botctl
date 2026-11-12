#!/usr/bin/env python3
"""BotCtl 维护服务：地图删减 + 定时喊话。

为什么需要它：手机 App 不能直接动服务器文件，ZenithProxy 也没有删除地图或
定时发言的命令。WebUI 插件只会发静态文件，不会删、也不会定时。

鉴权复用面板账号密码（不是新造一套密钥）：App 把面板用户名密码放在请求体里，
本服务在 localhost 登录面板验证。面板令牌绑签发 IP，跨机重放无效，所以不用 cookie 做主路径。

端点（POST 都要带 {"user":..,"pass":..}）
  GET  /api/health                          -> {"ok":true,...}
  POST /api/maps/delete  {"names":[...]}     -> {"success":true,"deleted":n}
  POST /api/maps/clear                       -> {"success":true,"deleted":n}
  POST /api/chat/get                         -> 定时喊话当前状态
  POST /api/chat/set     {"enabled":bool,"intervalSec":int,"jitterSec":int,
                          "messages":[..],"rotate":bool}   部分更新
  POST /api/chat/send    {"index":n}         -> 立刻发一条（不影响排程）
  POST /api/chat/test                        -> 只验证面板链路（跑 stats，不发公屏）

只删除 /root/ZenithProxy/maps 下的普通 *.png 文件名。
"""
import json
import os
import random
import re
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAPS_DIR = "/root/ZenithProxy/maps"
INSTALL_DIR = os.path.dirname(MAPS_DIR.rstrip("/")) or "/root/ZenithProxy"
PANEL = "http://127.0.0.1:8080"
LISTEN = ("0.0.0.0", 8081)   # 8081 已在云防火墙放行
SAFE_NAME = re.compile(r"^[A-Za-z0-9_.\-]+\.png$")

WEBUI_CONF = os.path.join(INSTALL_DIR, "plugins", "webui", "config.json")
CHAT_CONF = os.path.join(INSTALL_DIR, "chatloop.json")

MIN_INTERVAL = 30          # 秒，防手滑填 1 秒刷屏
MAX_MESSAGES = 50
MAX_TEXT = 200

_cfg_lock = threading.Lock()
_cookie = ""               # 缓存的本地面板 cookie


def log(msg):
    print(msg, file=sys.stderr, flush=True)


# ---------------------------------------------------------------- panel 调用

def panel_credentials():
    """从面板配置读用户名密码（本机 root 文件）。"""
    try:
        with open(WEBUI_CONF, encoding="utf-8") as f:
            auth = json.load(f).get("auth", {})
        return auth.get("username", ""), auth.get("password", "")
    except Exception as e:
        log("读面板配置失败: %s" % e)
        return "", ""


def panel_login():
    """在 localhost 登录面板，缓存 cookie。返回是否成功。"""
    global _cookie
    user, pwd = panel_credentials()
    if not user or not pwd:
        return False
    body = json.dumps({"username": user, "password": pwd}).encode()
    req = urllib.request.Request(PANEL + "/api/auth/login", data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=12) as r:
            raw = r.read().decode("utf-8", "replace")
            sc = r.headers.get("Set-Cookie", "")
            m = re.search(r"webui_token=[^;]+", sc)
            if r.status == 200 and m:
                _cookie = m.group(0)
                return True
            return r.status == 200 and '"success":true' in raw.replace(" ", "")
    except Exception as e:
        log("面板登录失败: %s" % e)
        return False


def panel_command(cmd):
    """执行一条 ZenithProxy 命令，返回输出文本。"""
    global _cookie
    for attempt in (1, 2):
        if not _cookie and not panel_login():
            return None
        body = json.dumps({"command": cmd}).encode()
        req = urllib.request.Request(PANEL + "/api/commands", data=body, method="POST")
        req.add_header("Content-Type", "application/json")
        req.add_header("Cookie", _cookie)
        try:
            with urllib.request.urlopen(req, timeout=25) as r:
                d = json.loads(r.read().decode("utf-8", "replace"))
                return "\n".join(d.get("output", []))
        except urllib.error.HTTPError as e:
            if e.code in (401, 403) and attempt == 1:
                _cookie = ""
                continue
            log("命令失败 HTTP %s: %s" % (e.code, cmd[:40]))
            return None
        except Exception as e:
            log("命令异常: %s" % e)
            return None
    return None


def bot_online():
    """面板 dashboard 的 connected 字段。"""
    global _cookie
    if not _cookie and not panel_login():
        return False
    req = urllib.request.Request(PANEL + "/api/dashboard")
    req.add_header("Cookie", _cookie)
    try:
        with urllib.request.urlopen(req, timeout=12) as r:
            d = json.loads(r.read().decode("utf-8", "replace"))
            return bool(d.get("connected"))
    except Exception:
        return False


# ---------------------------------------------------------------- 喊话配置

def default_chat_cfg():
    return {
        "enabled": False,
        "intervalSec": 300,
        "jitterSec": 30,
        "rotate": True,
        "messages": [],
        "idx": 0,
        "nextAt": 0.0,
        "lastSent": 0.0,
        "lastText": "",
        "sentCount": 0,
    }


def load_chat_cfg():
    cfg = default_chat_cfg()
    try:
        with open(CHAT_CONF, encoding="utf-8") as f:
            raw = json.load(f)
        if isinstance(raw, dict):
            cfg.update(raw)
    except Exception:
        pass
    return cfg


def save_chat_cfg(cfg):
    tmp = CHAT_CONF + ".tmp"
    try:
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(cfg, f, ensure_ascii=False, indent=2)
        os.replace(tmp, CHAT_CONF)
    except Exception as e:
        log("保存喊话配置失败: %s" % e)


def chat_state(cfg):
    return {
        "success": True,
        "enabled": bool(cfg["enabled"]),
        "intervalSec": int(cfg["intervalSec"]),
        "jitterSec": int(cfg["jitterSec"]),
        "rotate": bool(cfg["rotate"]),
        "messages": list(cfg["messages"]),
        "count": len(cfg["messages"]),
        "idx": int(cfg.get("idx", 0)),
        "nextAt": float(cfg.get("nextAt") or 0),
        "lastSent": float(cfg.get("lastSent") or 0),
        "lastText": cfg.get("lastText", ""),
        "sentCount": int(cfg.get("sentCount") or 0),
        "now": time.time(),
        "minInterval": MIN_INTERVAL,
    }


def next_interval(cfg):
    base = max(MIN_INTERVAL, int(cfg["intervalSec"]))
    jit = max(0, int(cfg["jitterSec"]))
    if jit:
        base += random.randint(-jit, jit)
    return max(MIN_INTERVAL, base)


def schedule_next(cfg, from_now=True):
    cfg["nextAt"] = (time.time() if from_now else 0) + next_interval(cfg)
    return cfg["nextAt"]


def send_one(cfg, index=None):
    msgs = cfg.get("messages") or []
    if not msgs:
        return False, "消息列表是空的"
    if index is None:
        idx = int(cfg.get("idx", 0)) % len(msgs) if cfg.get("rotate", True) else 0
    else:
        idx = int(index) % len(msgs)
    text = str(msgs[idx])[:MAX_TEXT]
    out = panel_command("sendMessage " + text)
    if out is None:
        return False, "面板命令失败（机器人可能离线）"
    cfg["lastSent"] = time.time()
    cfg["lastText"] = text
    cfg["sentCount"] = int(cfg.get("sentCount") or 0) + 1
    if cfg.get("rotate", True):
        cfg["idx"] = (idx + 1) % len(msgs)
    return True, out.strip() or "已发送"


def scheduler_loop():
    """每秒醒一次；到点就发下一条。"""
    log("定时喊话调度线程已启动")
    while True:
        try:
            time.sleep(1)
            with _cfg_lock:
                cfg = load_chat_cfg()
                if not cfg["enabled"] or not cfg["messages"]:
                    continue
                now = time.time()
                if not cfg.get("nextAt"):
                    schedule_next(cfg)
                    save_chat_cfg(cfg)
                    continue
                if now < float(cfg["nextAt"]):
                    continue
                if not bot_online():
                    # 机器人离线时不要空转刷屏，30 秒后再看
                    cfg["nextAt"] = now + 30
                    save_chat_cfg(cfg)
                    continue
                ok, info = send_one(cfg)
                log("自动喊话: %s (%s)" % ("成功" if ok else "失败", info[:60]))
                schedule_next(cfg)
                save_chat_cfg(cfg)
        except Exception as e:
            log("调度线程异常: %s" % e)
            time.sleep(5)


# ---------------------------------------------------------------- 地图

def cookie_is_valid(cookie):
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


# ---------------------------------------------------------------- HTTP

class Handler(BaseHTTPRequestHandler):
    server_version = "BotCtlHelper/1.1"
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
            cfg = load_chat_cfg()
            self._send(200, {"ok": True, "service": "botctl-helper",
                             "maps": len(list_png()),
                             "chatEnabled": bool(cfg["enabled"])})
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

        if not (credentials_valid(str(payload.get("user", "")), str(payload.get("pass", "")))
                or cookie_is_valid(self._cookie())):
            self._send(401, {"error": "面板账号或密码不对（App 里填的面板凭据已失效？）"})
            return

        # ---------------- 地图
        if self.path == "/api/maps/clear":
            self._send(200, {"success": True, "deleted": delete_maps(list_png())})
            return

        if self.path == "/api/maps/delete":
            names = payload.get("names", [])
            if not isinstance(names, list):
                self._send(400, {"error": "names 必须是数组"})
                return
            self._send(200, {"success": True, "deleted": delete_maps(names)})
            return

        # ---------------- 喊话
        if self.path == "/api/chat/get":
            with _cfg_lock:
                self._send(200, chat_state(load_chat_cfg()))
            return

        if self.path == "/api/chat/set":
            with _cfg_lock:
                cfg = load_chat_cfg()
                changed = False
                if "messages" in payload:
                    msgs = payload["messages"]
                    if not isinstance(msgs, list):
                        self._send(400, {"error": "messages 必须是数组"})
                        return
                    clean = []
                    for m in msgs:
                        if not isinstance(m, str):
                            continue
                        m = m.strip()
                        if m:
                            clean.append(m[:MAX_TEXT])
                    cfg["messages"] = clean[:MAX_MESSAGES]
                    cfg["idx"] = 0
                    changed = True
                if "intervalSec" in payload:
                    try:
                        v = int(payload["intervalSec"])
                    except Exception:
                        self._send(400, {"error": "intervalSec 必须是整数"})
                        return
                    if v < MIN_INTERVAL:
                        self._send(400, {"error": "间隔不能小于 %d 秒（防刷屏）" % MIN_INTERVAL})
                        return
                    cfg["intervalSec"] = v
                    changed = True
                if "jitterSec" in payload:
                    try:
                        cfg["jitterSec"] = max(0, int(payload["jitterSec"]))
                    except Exception:
                        pass
                    changed = True
                if "rotate" in payload:
                    cfg["rotate"] = bool(payload["rotate"])
                    changed = True
                if "enabled" in payload:
                    want = bool(payload["enabled"])
                    if want and not cfg["messages"]:
                        self._send(400, {"error": "消息列表是空的，先加一条再开"})
                        return
                    cfg["enabled"] = want
                    if want:
                        schedule_next(cfg)      # 开启后等一个间隔再发第一条
                    else:
                        cfg["nextAt"] = 0
                    changed = True
                if changed:
                    save_chat_cfg(cfg)
                self._send(200, chat_state(cfg))
            return

        if self.path == "/api/chat/send":
            with _cfg_lock:
                cfg = load_chat_cfg()
                idx = payload.get("index")
                ok, info = send_one(cfg, int(idx) if isinstance(idx, int) else None)
                save_chat_cfg(cfg)
                st = chat_state(cfg)
                st["success"] = ok
                st["message"] = info
                self._send(200 if ok else 502, st)
            return

        if self.path == "/api/chat/test":
            out = panel_command("stats")
            if out is None:
                self._send(502, {"success": False, "error": "面板命令链路不通"})
            else:
                self._send(200, {"success": True, "online": bot_online(),
                                 "output": out[:200]})
            return

        self._send(404, {"error": "not found"})


if __name__ == "__main__":
    threading.Thread(target=scheduler_loop, daemon=True).start()
    srv = ThreadingHTTPServer(LISTEN, Handler)
    log("botctl-helper listening on %s:%d (maps + chat)" % LISTEN)
    srv.serve_forever()
