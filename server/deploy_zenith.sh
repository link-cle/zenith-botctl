#!/usr/bin/env bash
# =============================================================================
# ZenithProxy + BotCtl 面板插件 一键部署
#
# 与旧版的区别：**不再安装 ZenithProxy-WebUI（wxh1024）**，
# 改用本仓库自带的 panel 插件（plugin/ 目录），一个 jar 提供：
#   认证 / dashboard / 命令 / SSE 日志 / 白名单 / 地图静态与删除 / 定时喊话
# 因此也不再需要 8081 那个额外服务。
#
# 用法（仓库版）：
#     bash scripts/fetch-upstream.sh          # 先下载 ZenithProxy.jar
#     sudo bash server/deploy_zenith.sh --server 你的目标服
#
# 或者把 ZenithProxy.jar 和本脚本放同一目录，直接跑。
# =============================================================================
set -uo pipefail

INSTALL_DIR="/root/ZenithProxy"
PANEL_PORT="8080"
PANEL_USER="admin"
PANEL_PWD=""
TARGET_SERVER="2b2t.org"
PROXY_IP=""
ENABLE_OFFLINE=1
KIT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$KIT_DIR/.." && pwd)"

while [ $# -gt 0 ]; do
  case "$1" in
    --dir)          INSTALL_DIR="$2"; shift 2 ;;
    --panel-port)   PANEL_PORT="$2"; shift 2 ;;
    --password)     PANEL_PWD="$2"; shift 2 ;;
    --server)       TARGET_SERVER="$2"; shift 2 ;;
    --proxy-ip)     PROXY_IP="$2"; shift 2 ;;
    --no-offline)   ENABLE_OFFLINE=0; shift ;;
    -h|--help)      sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "未知参数: $1"; exit 1 ;;
  esac
done

info() { printf '\033[36m[信息]\033[0m %s\n' "$*"; }
ok()   { printf '\033[32m[完成]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[注意]\033[0m %s\n' "$*"; }
die()  { printf '\033[31m[失败]\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" = "0" ] || die "请用 root 运行"

# ---------------------------------------------------------------- 找文件
find_file() {
  local n="$1" d
  for d in "$KIT_DIR" "$KIT_DIR/../dist" "$KIT_DIR/dist" "$PWD" "$PWD/dist"; do
    if [ -f "$d/$n" ]; then printf '%s/%s\n' "$(cd "$d" && pwd)" "$n"; return 0; fi
  done
  return 1
}

PLUGIN_DIR=""
for d in "$REPO_DIR/plugin" "$KIT_DIR/../plugin" "$KIT_DIR/plugin" "$PWD/plugin"; do
  [ -d "$d/src" ] && PLUGIN_DIR="$(cd "$d" && pwd)" && break
done
[ -n "$PLUGIN_DIR" ] || die "找不到 plugin/ 目录（面板插件源码）"

ZP_JAR="$(find_file ZenithProxy.jar)" || die "找不到 ZenithProxy.jar，先跑 bash scripts/fetch-upstream.sh"
info "机器人 jar : $ZP_JAR"
info "面板插件源 : $PLUGIN_DIR"

# ---------------------------------------------------------------- 依赖
info "安装依赖（Java 21 / python3 / tmux / curl）"
export DEBIAN_FRONTEND=noninteractive
if command -v apt-get >/dev/null 2>&1; then
  apt-get update -qq || warn "apt-get update 有报错，继续"
  apt-get install -y -qq openjdk-21-jdk-headless python3 tmux curl unzip ca-certificates 2>/dev/null \
    || apt-get install -y -qq openjdk-21-jre-headless openjdk-17-jdk-headless python3 tmux curl unzip \
    || die "依赖安装失败（需要 JDK，用来编译插件）"
else
  die "这个脚本按 Debian/Ubuntu 写"
fi

JAVAC=""
for c in /usr/lib/jvm/java-21-openjdk-amd64/bin/javac /usr/lib/jvm/java-17-openjdk-amd64/bin/javac; do
  [ -x "$c" ] && JAVAC="$c" && break
done
[ -n "$JAVAC" ] || JAVAC="$(command -v javac || true)"
[ -n "$JAVAC" ] || die "找不到 javac"
JAR_TOOL="$(dirname "$JAVAC")/jar"
[ -x "$JAR_TOOL" ] || JAR_TOOL="$(command -v jar || true)"
[ -n "$JAR_TOOL" ] || die "找不到 jar 工具"
info "javac: $JAVAC"

# ---------------------------------------------------------------- swap
mem_mb=$(awk '/MemTotal/{printf "%d", $2/1024}' /proc/meminfo)
if [ "$mem_mb" -lt 2048 ] && ! swapon --show | grep -q .; then
  info "内存 ${mem_mb}MB 偏小，建 2GB swap"
  fallocate -l 2G /swapfile 2>/dev/null && chmod 600 /swapfile \
    && mkswap /swapfile >/dev/null && swapon /swapfile \
    && grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
  ok "swap 已启用"
fi

# ---------------------------------------------------------------- 目录与 jar
info "部署到 $INSTALL_DIR"
mkdir -p "$INSTALL_DIR/plugins" "$INSTALL_DIR/maps" "$INSTALL_DIR/backup" "$INSTALL_DIR/tools"
cp -f "$ZP_JAR" "$INSTALL_DIR/ZenithProxy.jar"

# 面板密码：沿用已有，否则随机
if [ -z "$PANEL_PWD" ] && [ -f "$INSTALL_DIR/plugins/botctl/config.json" ]; then
  PANEL_PWD=$(python3 -c "import json;print(json.load(open('$INSTALL_DIR/plugins/botctl/config.json'))['password'])" 2>/dev/null || true)
fi
[ -z "$PANEL_PWD" ] && PANEL_PWD=$(python3 -c "
import random,string
a='ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789'
print(''.join(random.SystemRandom().choice(a) for _ in range(16)))")

# ---------------------------------------------------------------- 编译面板插件
info "编译 BotCtl 面板插件（不需要联网）"
BUILD="$INSTALL_DIR/build-plugin"
rm -rf "$BUILD"; mkdir -p "$BUILD/classes"
"$JAVAC" -encoding UTF-8 -source 17 -target 17 -nowarn \
  -classpath "$INSTALL_DIR/ZenithProxy.jar" -d "$BUILD/classes" \
  $(find "$PLUGIN_DIR/src" -name '*.java') 2>&1 | head -30
CNT=$(find "$BUILD/classes" -name '*.class' | wc -l)
[ "$CNT" -ge 3 ] || die "插件编译失败（classes=$CNT）"
cp -f "$PLUGIN_DIR/zenithproxy.plugin.json" "$BUILD/classes/"
(cd "$BUILD/classes" && "$JAR_TOOL" cf "$INSTALL_DIR/plugins/botctl-panel.jar" com zenithproxy.plugin.json) \
  || die "插件打包失败"
ok "插件已构建：plugins/botctl-panel.jar（$CNT 个类）"

mkdir -p "$INSTALL_DIR/plugins/botctl"
cat > "$INSTALL_DIR/plugins/botctl/config.json" <<EOF
{
  "port": $PANEL_PORT,
  "username": "$PANEL_USER",
  "password": "$PANEL_PWD",
  "tokenExpiryMs": 86400000
}
EOF
chmod 600 "$INSTALL_DIR/plugins/botctl/config.json"

# 旧 WebUI 若存在，移走避免抢 8080
for f in "$INSTALL_DIR"/plugins/ZenithProxy-WebUI-*.jar; do
  [ -e "$f" ] || continue
  mv -f "$f" "$INSTALL_DIR/backup/" && warn "已移走旧 WebUI 插件（$(basename "$f")）"
done

# ---------------------------------------------------------------- 地图索引
info "安装地图索引定时器（每 20 秒写一次索引文件）"
sed "s|/root/ZenithProxy/maps|$INSTALL_DIR/maps|g" "$KIT_DIR/mapindex.py" > "$INSTALL_DIR/tools/mapindex.py"
cat > /etc/systemd/system/mapindex.service <<EOF
[Unit]
Description=Build map image index for BotCtl panel

[Service]
Type=oneshot
ExecStart=/usr/bin/python3 $INSTALL_DIR/tools/mapindex.py
EOF
cat > /etc/systemd/system/mapindex.timer <<'EOF'
[Unit]
Description=Refresh map image index every 20s

[Timer]
OnBootSec=20s
OnUnitActiveSec=20s
AccuracySec=1s
Unit=mapindex.service

[Install]
WantedBy=timers.target
EOF

# ---------------------------------------------------------------- systemd
info "创建机器人的 systemd 服务"
cat > /etc/systemd/system/zenith.service <<EOF
[Unit]
Description=ZenithProxy (Minecraft proxy bot, tmux session "zenith")
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=$INSTALL_DIR
ExecStart=/bin/bash -c '/usr/bin/tmux kill-session -t zenith 2>/dev/null; /usr/bin/tmux new-session -d -s zenith "cd $INSTALL_DIR && exec java -Xmx500m -jar ZenithProxy.jar 2>&1 | tee -a $INSTALL_DIR/console.log"'
ExecStop=/bin/bash -c '/usr/bin/tmux kill-session -t zenith 2>/dev/null; /usr/bin/pkill -f "ZenithProxy.jar" 2>/dev/null; exit 0'
TimeoutStartSec=30

[Install]
WantedBy=multi-user.target
EOF

# 旧的 8081 服务如果存在就停掉，避免与插件重复喊话
systemctl disable --now mapadmin 2>/dev/null || true

systemctl daemon-reload
systemctl enable --now zenith >/dev/null 2>&1
systemctl enable --now mapindex.timer >/dev/null 2>&1
ok "服务已启动并设为开机自启"

# ---------------------------------------------------------------- 等面板
info "等待机器人启动与插件加载（约 45 秒）"
for i in $(seq 1 60); do
  sleep 2
  if curl -sS -m 3 -o /dev/null "http://127.0.0.1:$PANEL_PORT/api/health" 2>/dev/null; then break; fi
done
curl -sS -m 3 "http://127.0.0.1:$PANEL_PORT/api/health" >/dev/null \
  || die "面板没起来，看 $INSTALL_DIR/console.log 里的 Plugin 段"

# ---------------------------------------------------------------- 配置机器人
info "配置目标服务器与离线支持"
api() {
  curl -sS -m 30 -b /tmp/.bcookie -X POST "http://127.0.0.1:$PANEL_PORT/api/commands" \
       -H 'Content-Type: application/json' \
       -d "$(python3 -c "import json,sys;print(json.dumps({'command':sys.argv[1]}))" "$1")" >/dev/null 2>&1
}
curl -sS -m 10 -c /tmp/.bcookie -X POST "http://127.0.0.1:$PANEL_PORT/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$PANEL_USER\",\"password\":\"$PANEL_PWD\"}" >/dev/null \
  || die "面板登录失败"

[ -z "$PROXY_IP" ] && PROXY_IP=$(curl -fsS --max-time 6 https://api.ipify.org 2>/dev/null \
  || ip route get 1.1.1.1 2>/dev/null | awk '{print $7; exit}' || echo "127.0.0.1")
info "对外显示的代理地址：$PROXY_IP:25565"

api "server $TARGET_SERVER"
api "serverConnection port 25565"
api "serverConnection proxyIP $PROXY_IP:25565"
api "clientConnection autoConnect on"
api "serverConnection autoConnectOnLogin on"
api "autoUpdate off"
[ "$ENABLE_OFFLINE" = "1" ] && api "unsupported allowOfflinePlayers on"

if [ "$ENABLE_OFFLINE" = "1" ]; then
  systemctl stop zenith; sleep 4
  python3 - "$INSTALL_DIR" <<'PY'
import json, sys
p = sys.argv[1] + '/config.json'
c = json.load(open(p))
c['server']['verifyUsers'] = False
json.dump(c, open(p, 'w'), indent=2)
print('  server.verifyUsers = False')
PY
  systemctl start zenith; sleep 30
fi

# ---------------------------------------------------------------- 自检
info "自检"
sleep 3
login_code=$(curl -sS -m 10 -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$PANEL_PORT/api/auth/login" \
  -H 'Content-Type: application/json' -d "{\"username\":\"$PANEL_USER\",\"password\":\"$PANEL_PWD\"}")
dash=$(curl -sS -m 20 -b /tmp/.bcookie "http://127.0.0.1:$PANEL_PORT/api/dashboard" 2>/dev/null \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print('connected=%s user=%s'%(d['connected'],d['username']))" 2>/dev/null || echo "读取失败")
plug=$(ls -1 "$INSTALL_DIR"/plugins/*.jar 2>/dev/null | xargs -r -n1 basename | tr '\n' ' ')
rm -f /tmp/.bcookie

echo
echo "=============================================================="
echo " 部署完成"
echo "=============================================================="
echo " 面板地址   : http://$PROXY_IP:$PANEL_PORT"
echo " 面板用户名 : $PANEL_USER"
echo " 面板密码   : $PANEL_PWD"
echo " 已装插件   : $plug"
echo " 监听       : 仅 $PANEL_PORT（地图/喊话/日志都在这一个端口）"
echo " 安装目录   : $INSTALL_DIR"
echo " 自检       : 面板登录 HTTP=$login_code（200 正常）"
echo "              机器人     $dash"
echo
echo " 还需要你手动做："
echo "   1) 云防火墙放行 TCP $PANEL_PORT 与 25565（8081 不再需要）"
echo "   2) 手机 App「账号」页填：地址=$PROXY_IP:$PANEL_PORT 用户=$PANEL_USER 密码=$PANEL_PWD"
echo "   3) 首次上线在 App 点「上线」完成微软设备码授权"
echo "   4) 白名单：App「白名单」页加你自己的游戏 ID"
echo "=============================================================="
