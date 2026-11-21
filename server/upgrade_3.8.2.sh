#!/usr/bin/env bash
# =============================================================================
# ZenithProxy 3.7.3(1.21.4) -> 3.8.2(26.3.0) 升级脚本
#   需要 JDK 25（新 jar 是 class 69.0），装在 /opt/jdk25
#   面板插件会用新 jar 重新编译
#   任何一步失败都可以用 backup 里的东西回滚
# =============================================================================
set -uo pipefail

DIR=/root/ZenithProxy
NEW_JAR=${NEW_JAR:-/root/ZenithProxy-3.8.2-26.3.0.jar}
JDK=${JDK:-/opt/jdk25}
PLUGIN_SRC=/root/botctl-panel
STAMP=$(date +%Y%m%d-%H%M%S)
BK="$DIR/backup/pre-3.8.2-$STAMP"

die() { echo "!!! $*" >&2; exit 1; }
info() { echo "[信息] $*"; }
ok() { echo "[完成] $*"; }

[ -f "$NEW_JAR" ] || die "找不到新 jar: $NEW_JAR"
[ -x "$JDK/bin/java" ] || die "找不到 JDK25: $JDK/bin/java"
[ -d "$PLUGIN_SRC/src" ] || die "找不到插件源码: $PLUGIN_SRC"

# ---------------------------------------------------------------- 0. 备份
info "备份当前环境 -> $BK"
mkdir -p "$BK"
cp -a "$DIR/ZenithProxy.jar" "$BK/" 2>/dev/null
cp -a "$DIR/config.json" "$BK/" 2>/dev/null
cp -a "$DIR/launch_config.json" "$BK/" 2>/dev/null
cp -a "$DIR/mc_auth_cache.json" "$BK/" 2>/dev/null
cp -a "$DIR/chatloop.json" "$BK/" 2>/dev/null
cp -a "$DIR/plugins" "$BK/plugins"
cp -a /etc/systemd/system/zenith.service "$BK/" 2>/dev/null
ls -la "$BK" | head -12
ok "备份完成"

# ---------------------------------------------------------------- 1. 编译插件
info "用 JDK25 重新编译面板插件（-proc:none 跳过注解处理器）"
rm -rf "$PLUGIN_SRC/build25"; mkdir -p "$PLUGIN_SRC/build25/classes"
"$JDK/bin/javac" -encoding UTF-8 -source 17 -target 17 -proc:none -nowarn \
  -classpath "$NEW_JAR" -d "$PLUGIN_SRC/build25/classes" \
  $(find "$PLUGIN_SRC/src" -name '*.java') 2>&1 | head -30
CNT=$(find "$PLUGIN_SRC/build25/classes" -name '*.class' | wc -l)
[ "$CNT" -ge 3 ] || die "插件在新 jar 上编译失败（classes=$CNT）"
cp -f "$PLUGIN_SRC/zenithproxy.plugin.json" "$PLUGIN_SRC/build25/classes/"
(cd "$PLUGIN_SRC/build25/classes" && "$JDK/bin/jar" cf "$PLUGIN_SRC/botctl-panel-1.0.0.jar" com zenithproxy.plugin.json)
ok "插件重编完成（$CNT 个类）"

# ---------------------------------------------------------------- 2. 停服换 jar
info "停止机器人"
systemctl stop zenith; sleep 4; pkill -f ZenithProxy.jar 2>/dev/null; sleep 2

info "替换 jar 与插件"
cp -f "$NEW_JAR" "$DIR/ZenithProxy.jar"
cp -f "$PLUGIN_SRC/botctl-panel-1.0.0.jar" "$DIR/plugins/"

# ---------------------------------------------------------------- 3. systemd 用 JDK25
info "把 systemd 的 java 指向 JDK25"
cat > /etc/systemd/system/zenith.service <<EOF
[Unit]
Description=ZenithProxy (Minecraft proxy bot, tmux session "zenith")
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=$DIR
ExecStart=/bin/bash -c '/usr/bin/tmux kill-session -t zenith 2>/dev/null; /usr/bin/tmux new-session -d -s zenith "cd $DIR && exec $JDK/bin/java -Xmx500m -jar ZenithProxy.jar 2>&1 | tee -a $DIR/console.log"'
ExecStop=/bin/bash -c '/usr/bin/tmux kill-session -t zenith 2>/dev/null; /usr/bin/pkill -f "ZenithProxy.jar" 2>/dev/null; exit 0'
TimeoutStartSec=30

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload

info "启动（首次可能要迁移配置）"
systemctl start zenith
sleep 60

# ---------------------------------------------------------------- 4. 自检
echo
info "自检"
echo "  服务: $(systemctl is-active zenith)"
echo "  端口: $(ss -lntp | grep -c ':8080') 个 8080 监听"
if curl -sS -m 5 http://127.0.0.1:8080/api/health >/dev/null 2>&1; then
  echo "  面板: $(curl -sS -m 5 http://127.0.0.1:8080/api/health)"
  ok "面板已起来"
  CK=/tmp/up.ck; rm -f $CK
  if curl -sS -m 10 -c $CK -X POST http://127.0.0.1:8080/api/auth/login \
       -H 'Content-Type: application/json' -d '{"username":"admin","password":"link7891"}' | grep -q success; then
    echo "  登录: OK"
    curl -sS -m 25 -b $CK http://127.0.0.1:8080/api/dashboard | python3 -c "
import sys,json;d=json.load(sys.stdin)
print('  机器人: connected=%s user=%s' % (d.get('connected'), d.get('username')))
print('  版本: version=%s via=%s' % (d.get('version'), d.get('viaVersion')))" 2>&1
  else
    echo "  登录: 失败（面板可能用了新配置）"
  fi
else
  echo "  面板: 没起来"
  echo "--- console.log 末尾 ---"
  tail -40 "$DIR/console.log" | sed 's/\x1b\[[0-9;]*m//g'
  echo
  echo "回滚方式："
  echo "  systemctl stop zenith"
  echo "  cp $BK/ZenithProxy.jar $DIR/ZenithProxy.jar"
  echo "  cp -a $BK/plugins/. $DIR/plugins/"
  echo "  cp $BK/zenith.service /etc/systemd/system/ && systemctl daemon-reload"
  echo "  cp $BK/config.json $DIR/ 2>/dev/null"
  echo "  systemctl start zenith"
  die "升级后面板未就绪"
fi

echo
echo "=============================================================="
echo " 升级完成：$($JDK/bin/java -version 2>&1 | head -1)"
echo " 新 jar : $(ls -la $DIR/ZenithProxy.jar | awk '{print $5" bytes"}')"
echo " 备份   : $BK"
echo "=============================================================="
