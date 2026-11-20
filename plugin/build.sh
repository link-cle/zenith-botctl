#!/usr/bin/env bash
# 构建 BotCtl 面板插件（不需要 Gradle，也不需要联网）
#
#   bash build.sh                 # 用 ZenithProxy.jar 作为 classpath
#   ZENITH_JAR=/path/to.jar bash build.sh
#
# 产物：botctl-panel-<版本>.jar，丢进 ZenithProxy 的 plugins/ 目录即可。
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE" || exit 1

ZENITH_JAR="${ZENITH_JAR:-/root/ZenithProxy/ZenithProxy.jar}"
OUT_DIR="$HERE/build"
VERSION="$(grep -o '"version"[^,]*' zenithproxy.plugin.json | head -1 | sed 's/.*"\([^"]*\)"$/\1/')"
[ -z "$VERSION" ] && VERSION=dev

die() { echo "!!! $*" >&2; exit 1; }

echo "== 环境 =="
[ -f "$ZENITH_JAR" ] || die "找不到 ZenithProxy.jar（用 ZENITH_JAR=... 指定）"
JAVAC="${JAVAC:-}"
if [ -z "$JAVAC" ]; then
  for c in /usr/lib/jvm/java-21-openjdk-amd64/bin/javac /usr/lib/jvm/java-17-openjdk-amd64/bin/javac; do
    [ -x "$c" ] && JAVAC="$c" && break
  done
fi
JAVAC="${JAVAC:-javac}"
JAR_TOOL="${JAR_TOOL:-$(dirname "$JAVAC")/jar}"
[ -x "$JAR_TOOL" ] || JAR_TOOL=jar
echo "  javac: $JAVAC"
echo "  jar  : $JAR_TOOL"
echo "  zenith: $ZENITH_JAR"

echo
echo "== 编译 =="
rm -rf "$OUT_DIR"; mkdir -p "$OUT_DIR/classes"
"$JAVAC" -encoding UTF-8 -source 17 -target 17 -nowarn \
  -classpath "$ZENITH_JAR" -d "$OUT_DIR/classes" \
  $(find src -name '*.java') 2>&1 | head -40
CNT=$(find "$OUT_DIR/classes" -name '*.class' | wc -l)
echo "  classes: $CNT"
[ "$CNT" -lt 3 ] && die "编译失败"

echo
echo "== 打包 =="
cp zenithproxy.plugin.json "$OUT_DIR/classes/"
OUT="$HERE/botctl-panel-$VERSION.jar"
rm -f "$OUT"
(cd "$OUT_DIR/classes" && "$JAR_TOOL" cf "$OUT" com zenithproxy.plugin.json) || die "打包失败"
echo "  产物: $OUT ($(stat -c %s "$OUT") bytes)"

echo
echo "== 校验 =="
unzip -l "$OUT" | grep -E 'zenithproxy.plugin.json|BotCtlPlugin.class' | sed 's/^/  /'
echo
echo "BUILD_DONE $OUT"
