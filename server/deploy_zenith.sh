#!/usr/bin/env bash
# =============================================================================
# ZenithProxy 一键部署（含 WebUI 面板 + 地图索引 + 地图删减服务）
#
# 用法（仓库版）：
#     bash scripts/fetch-upstream.sh              # 先下好两个上游 jar
#     sudo bash server/deploy_zenith.sh --server 你的目标服
#
# 或者把 jar 和本脚本放同一目录，直接：
#     sudo bash deploy_zenith.sh --password MyPass123 --server mc.example.com
#
# 默认值取自一台实际运行的机器，脚本可重复执行（幂等）。
# =============================================================================
set -uo pipefail

# ---------------------------------------------------------------- 默认参数
INSTALL_DIR="/root/ZenithProxy"
PANEL_PORT="8080"
MAP_PORT="8081"
PANEL_USER="admin"
PANEL_PWD=""                       # 空 = 自动生成 16 位随机密码
TARGET_SERVER="2b2t.org"        # 上游默认服；用 --server 改成你的目标服
PROXY_IP=""                        # 空 = 自动探测公网 IP
ENABLE_OFFLINE=1                   # 1 = 允许离线玩家连代理（verifyUsers=false + allowOfflinePlayers on）
KIT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---------------------------------------------------------------- 参数解析
while [ $# -gt 0 ]; do
  case "$1" in
    --dir)            INSTALL_DIR="$2"; shift 2 ;;
    --panel-port)     PANEL_PORT="$2"; shift 2 ;;
    --map-port)       MAP_PORT="$2"; shift 2 ;;
    --password)       PANEL_PWD="$2"; shift 2 ;;
    --server)         TARGET_SERVER="$2"; shift 2 ;;
    --proxy-ip)       PROXY_IP="$2"; shift 2 ;;
    --no-offline)     ENABLE_OFFLINE=0; shift ;;
    -h|--help)        sed -n '2,10p' "$0"; exit 0 ;;
    *) echo "未知参数: $1"; exit 1 ;;
  esac
done

info() { printf '\033[36m[信息]\033[0m %s\n' "$*"; }
ok()   { printf '\033[32m[完成]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[注意]\033[0m %s\n' "$*"; }
die()  { printf '\033[31m[失败]\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" = "0" ] || die "请用 root 运行（sudo bash $0）"

# ---------------------------------------------------------------- 0. 检查套件
info "脚本目录：$KIT_DIR"

# jar 可能在脚本同级（套件包），也可能在仓库的 dist/（用 fetch-upstream.sh 下的）
find_file() {   # find_file <文件名> -> 打印绝对路径
  local n="$1" d
  for d in "$KIT_DIR" "$KIT_DIR/../dist" "$KIT_DIR/dist" "$PWD" "$PWD/dist"; do
    if [ -f "$d/$n" ]; then
      printf '%s/%s\n' "$(cd "$d" && pwd)" "$n"
      return 0
    fi
  done
  return 1
}

ZP_JAR="$(find_file ZenithProxy.jar)" \
  || die "找不到 ZenithProxy.jar。先跑 bash scripts/fetch-upstream.sh，或把它放到脚本同级目录"
WU_JAR="$(find_file ZenithProxy-WebUI-1.4.0.jar)" \
  || die "找不到 ZenithProxy-WebUI-1.4.0.jar。先跑 bash scripts/fetch-upstream.sh"
for f in mapindex.py mapapi.py; do
  [ -f "$KIT_DIR/$f" ] || die "套件里缺文件：$f"
done
info "机器人 jar : $ZP_JAR"
info "面板插件   : $WU_JAR"

# ---------------------------------------------------------------- 1. 依赖
info "安装依赖（Java 21 / python3 / tmux / curl）"
export DEBIAN_FRONTEND=noninteractive
if command -v apt-get >/dev/null 2>&1; then
  apt-get update -qq || warn "apt-get update 有报错，继续尝试"
  apt-get install -y -qq openjdk-21-jre-headless python3 tmux curl unzip ca-certificates 2>/dev/null \
    || apt-get install -y -qq openjdk-21-jdk-headless python3 tmux curl unzip ca-certificates \
    || die "依赖安装失败，请手动装 Java 21"
else
  die "这个脚本按 Debian/Ubuntu 写的；CentOS 请手动装 openjdk-21 python3 tmux curl"
fi
java -version 2>&1 | head -1 | sed 's/^/  /'

# ---------------------------------------------------------------- 2. swap
mem_mb=$(awk '/MemTotal/{printf "%d", $2/1024}' /proc/meminfo)
if [ "$mem_mb" -lt 2048 ] && ! swapon --show | grep -q .; then
  info "内存 ${mem_mb}MB 偏小，创建 2GB swap（避免整机卡死）"
  fallocate -l 2G /swapfile 2>/dev/null && chmod 600 /swapfile \
    && mkswap /swapfile >/dev/null && swapon /swapfile \
    && grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
  ok "swap 已启用"
fi

# ---------------------------------------------------------------- 3. 目录与文件
info "部署到 $INSTALL_DIR"
mkdir -p "$INSTALL_DIR/plugins/webui" "$INSTALL_DIR/maps" "$INSTALL_DIR/backup" "$INSTALL_DIR/tools"
cp -f "$ZP_JAR" "$INSTALL_DIR/ZenithProxy.jar"
cp -f "$WU_JAR" "$INSTALL_DIR/plugins/"

# 面板密码：已有配置且没显式指定就沿用，否则生成随机
if [ -z "$PANEL_PWD" ]; then
  if [ -f "$INSTALL_DIR/plugins/webui/config.json" ]; then
    PANEL_PWD=$(python3 -c "import json;print(json.load(open('$INSTALL_DIR/plugins/webui/config.json'))['auth']['password'])" 2>/dev/null || true)
  fi
  [ -z "$PANEL_PWD" ] && PANEL_PWD=$(python3 -c "
import random,string
a='ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789'
print(''.join(random.SystemRandom().choice(a) for _ in range(16)))")
fi

# ---------------------------------------------------------------- 4. 面板配置
info "写面板配置（端口 $PANEL_PORT，认证开启）"
cat > "$INSTALL_DIR/plugins/webui/config.json" <<EOF
{
  "enabled": true,
  "port": $PANEL_PORT,
  "auth": {
    "enabled": true,
    "username": "$PANEL_USER",
    "password": "$PANEL_PWD",
    "tokenExpiry": 2592000000
  },
  "authBypasser": {
    "enabled": false,
    "rewriteOfflineUuid": false,
    "bypassAuthenticateHello": false
  }
}
EOF
chmod 600 "$INSTALL_DIR/plugins/webui/config.json"
ok "authBypasser 已关闭（开着会让正版账号登录被服端拒绝：already registered by an online account）"

# ---------------------------------------------------------------- 5. 地图静态目录
mkdir -p "$INSTALL_DIR/plugins/webui/web"
if [ ! -e "$INSTALL_DIR/plugins/webui/web/maps" ]; then
  ln -s "$INSTALL_DIR/maps" "$INSTALL_DIR/plugins/webui/web/maps"
fi
ok "已建软链 web/maps → $INSTALL_DIR/maps（用现有面板端口就能取地图 PNG）"

# ---------------------------------------------------------------- 6. 地图脚本
info "安装地图脚本（索引 + 删图服务，端口 $MAP_PORT）"
sed "s|/root/ZenithProxy/maps|$INSTALL_DIR/maps|g" "$KIT_DIR/mapindex.py" > "$INSTALL_DIR/tools/mapindex.py"
sed -e "s|/root/ZenithProxy/maps|$INSTALL_DIR/maps|g" \
    -e "s|(\"0.0.0.0\", 8081)|(\"0.0.0.0\", $MAP_PORT)|" \
    "$KIT_DIR/mapapi.py" > "$INSTALL_DIR/tools/mapapi.py"

# ---------------------------------------------------------------- 7. systemd
info "创建 systemd 服务"
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

cat > /etc/systemd/system/mapindex.service <<EOF
[Unit]
Description=Build map image index for BotCtl

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

cat > /etc/systemd/system/mapadmin.service <<EOF
[Unit]
Description=Map maintenance service for BotCtl (delete rendered maps)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/usr/bin/python3 $INSTALL_DIR/tools/mapapi.py
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now zenith >/dev/null 2>&1
systemctl enable --now mapindex.timer >/dev/null 2>&1
systemctl enable --now mapadmin >/dev/null 2>&1
ok "服务已启动并设为开机自启"

# ---------------------------------------------------------------- 8. 等面板
info "等待机器人首次启动（生成 config.json，约 40 秒）"
for i in $(seq 1 40); do
  sleep 2
  if curl -sS -m 3 -o /dev/null "http://127.0.0.1:$PANEL_PORT/api/auth/status" 2>/dev/null; then break; fi
done
curl -sS -m 3 -o /dev/null "http://127.0.0.1:$PANEL_PORT/api/auth/status" \
  || die "面板没起来，看 $INSTALL_DIR/console.log"

# ---------------------------------------------------------------- 9. 目标服务器与离线支持
info "配置目标服务器 $TARGET_SERVER 与离线玩家支持"
api() {  # api <命令>
  curl -sS -m 30 -b /tmp/.zcookie -X POST "http://127.0.0.1:$PANEL_PORT/api/commands" \
       -H 'Content-Type: application/json' \
       -d "$(python3 -c "import json,sys;print(json.dumps({'command':sys.argv[1]}))" "$1")" >/dev/null 2>&1
}
curl -sS -m 10 -c /tmp/.zcookie -X POST "http://127.0.0.1:$PANEL_PORT/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$PANEL_USER\",\"password\":\"$PANEL_PWD\"}" >/dev/null \
  || die "面板登录失败（密码不对？）"

[ -z "$PROXY_IP" ] && PROXY_IP=$(curl -fsS --max-time 6 https://api.ipify.org 2>/dev/null \
  || ip route get 1.1.1.1 2>/dev/null | awk '{print $7; exit}' || echo "127.0.0.1")
info "对外显示的代理地址：$PROXY_IP:$PANEL_PORT"

api "server $TARGET_SERVER"
api "serverConnection port 25565"
api "serverConnection proxyIP $PROXY_IP:25565"
api "clientConnection autoConnect on"
api "serverConnection autoConnectOnLogin on"
api "autoUpdate off"
if [ "$ENABLE_OFFLINE" = "1" ]; then
  api "unsupported allowOfflinePlayers on"
fi

# verifyUsers 没有命令入口，只能改文件；必须停服务再改，否则退出时会被内存回写覆盖
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

# ---------------------------------------------------------------- 10. 自检
info "自检"
sleep 3
login_code=$(curl -sS -m 10 -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$PANEL_PORT/api/auth/login" \
  -H 'Content-Type: application/json' -d "{\"username\":\"$PANEL_USER\",\"password\":\"$PANEL_PWD\"}")
map_code=$(curl -sS -m 10 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$MAP_PORT/api/health")
bot_state=$(curl -sS -m 10 -b /tmp/.zcookie "http://127.0.0.1:$PANEL_PORT/api/dashboard" 2>/dev/null \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print('connected=%s user=%s'%(d['connected'],d['username']))" 2>/dev/null || echo "读取失败")
rm -f /tmp/.zcookie

echo
echo "=============================================================="
echo " 部署完成"
echo "=============================================================="
echo " 面板地址   : http://$PROXY_IP:$PANEL_PORT"
echo " 面板用户名 : $PANEL_USER"
echo " 面板密码   : $PANEL_PWD"
echo " 地图服务   : 端口 $MAP_PORT（删图用，App 会自己调）"
echo " 安装目录   : $INSTALL_DIR"
echo " 自检       : 面板登录 HTTP=$login_code（200 为正常）"
echo "              地图服务 HTTP=$map_code（200 为正常）"
echo "              机器人     $bot_state"
echo
echo " 还需要你手动做："
echo "   1) 云厂商防火墙放行 TCP $PANEL_PORT 与 $MAP_PORT"
echo "   2) 玩家接入端口 TCP 25565 也要放行"
echo "   3) 手机 App「账号」页填：地址=$PROXY_IP:$PANEL_PORT 用户=$PANEL_USER 密码=$PANEL_PWD"
echo "   4) 首次上线要在 App 点「上线」，按提示完成微软设备码授权"
echo "   5) 白名单：App「白名单」页加你自己的游戏 ID"
echo "=============================================================="
