#!/usr/bin/env bash
# 构建 BotCtl APK —— 不用 Gradle、不用 AndroidX、不联网。
#
# 可通过环境变量覆盖（CI 里用得上）：
#   ANDROID_SDK_ROOT / ANDROID_HOME   根目录（默认 /opt/android）
#   BUILD_TOOLS_DIR                   build-tools 目录（默认 $SDK/build-tools）
#   ANDROID_JAR                       android.jar（默认 $SDK/platform-34/android.jar）
#   JAVAC                             javac 路径（默认自动找 JDK 17）
#   BOTCTL_KEYSTORE                   keystore 路径（默认 ./botctl.keystore，不存在则生成）
set -u

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android}}"
BT="${BUILD_TOOLS_DIR:-$SDK/build-tools}"
AJ="${ANDROID_JAR:-$SDK/platform-34/android.jar}"
PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD=$PROJ/build
VER=$(grep -o 'versionName="[^"]*"' "$PROJ/AndroidManifest.xml" | head -1 | cut -d'"' -f2)
VER=${VER:-dev}

die() { echo "!!! $*"; exit 1; }

echo "===== 0) toolchain ====="
echo "  SDK: $SDK"
for f in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$AJ"; do
  [ -e "$f" ] || die "missing $f（用 ANDROID_SDK_ROOT / BUILD_TOOLS_DIR / ANDROID_JAR 指定）"
done
# IMPORTANT: javac 21 会给匿名类构造函数写入参数名为空的 MethodParameters 属性，
# d8/R8 读到 null 名字直接 NPE（Cannot invoke String.length()）。javac 17 不写这个属性。
# 所以优先用 JDK 17：JAVAC 环境变量 > $JAVA_HOME/bin/javac（仅当是 17）> 系统 JDK 17 > javac
JC="${JAVAC:-}"
if [ -z "$JC" ]; then
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ] \
     && "$JAVA_HOME/bin/javac" -version 2>&1 | grep -q '^javac 17'; then
    JC="$JAVA_HOME/bin/javac"
  else
    for cand in /usr/lib/jvm/java-17-openjdk-amd64/bin/javac \
                /usr/lib/jvm/java-1.17.0-openjdk-amd64/bin/javac; do
      [ -x "$cand" ] && JC=$cand && break
    done
  fi
fi
JC=${JC:-javac}
echo "  javac: $JC"; "$JC" -version 2>&1
case "$("$JC" -version 2>&1)" in
  *"javac 17"*) : ;;
  *) echo "  ⚠ 警告：不是 javac 17，d8 可能因 MethodParameters 崩溃（见上）" ;;
esac

echo
echo
echo "===== 1) 资源 aapt2 compile + link（生成 R.java）====="
rm -rf "$BUILD"; mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/gen" "$BUILD/res"
cd "$PROJ" || die "no project dir"
RES_ZIP=""
if [ -d res ]; then
  "$BT/aapt2" compile --dir res -o "$BUILD/res/res.zip" 2>&1 | head -20
  [ -f "$BUILD/res/res.zip" ] || die "aapt2 compile failed"
  RES_ZIP="$BUILD/res/res.zip"
  echo "  资源已编译: $(stat -c %s "$RES_ZIP") bytes"
fi
"$BT/aapt2" link -o "$BUILD/base.apk" -I "$AJ" --manifest AndroidManifest.xml \
  --java "$BUILD/gen" $RES_ZIP 2>&1 | head -20
[ -f "$BUILD/base.apk" ] || die "aapt2 link failed"
echo "  base.apk $(stat -c %s "$BUILD/base.apk") bytes"

echo
echo "===== 2) javac ====="
R_JAVA="$(find "$BUILD/gen" -name 'R.java' 2>/dev/null | head -1)"
[ -n "$R_JAVA" ] && echo "  R.java: $R_JAVA"
"$JC" -encoding UTF-8 -source 8 -target 8 -nowarn \
      -bootclasspath "$AJ" -d "$BUILD/classes" \
      src/com/dsh/botctl/*.java $R_JAVA 2>&1 | grep -vE 'bootstrap class path|source value 8|target value 8|deprecat|^[0-9]+ warning' || true
CNT=$(ls "$BUILD/classes/com/dsh/botctl/"*.class 2>/dev/null | wc -l)
echo "  classes: $CNT"
if [ "$CNT" -lt 5 ]; then
  echo "  --- full javac output ---"
  "$JC" -encoding UTF-8 -source 8 -target 8 -bootclasspath "$AJ" -d /tmp/errc \
       src/com/dsh/botctl/*.java $R_JAVA 2>&1 | head -40
  die "compile failed"
fi

echo
echo "===== 3) d8 ====="
"$BT/d8" --lib "$AJ" --min-api 21 --output "$BUILD/dex" \
  "$BUILD/classes/com/dsh/botctl/"*.class 2>&1 | head -10
[ -f "$BUILD/dex/classes.dex" ] || die "no classes.dex"
echo "  classes.dex $(stat -c %s "$BUILD/dex/classes.dex") bytes"

echo
echo "===== 4) inject dex ====="
python3 - "$BUILD" <<'PY'
import sys, zipfile, shutil, os
b = sys.argv[1]
base = os.path.join(b, 'base.apk'); dex = os.path.join(b, 'dex', 'classes.dex')
tmp = os.path.join(b, 'w.apk')
with zipfile.ZipFile(base) as zin, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
    for it in zin.infolist():
        zout.writestr(it, zin.read(it.filename))
    zout.write(dex, 'classes.dex')
shutil.move(tmp, base)
with zipfile.ZipFile(base) as z:
    print('  entries:', ', '.join(z.namelist()))
PY

echo
echo "===== 5) align + sign ====="
cd "$BUILD" || die "cd build"
"$BT/zipalign" -f 4 base.apk aligned.apk || die "zipalign failed"

# keystore 必须放在 $BUILD 之外（第 1 步会清空 $BUILD）；保持稳定才能原地升级。
# 口令一律从环境变量来，不写死在脚本里：export BOTCTL_KS_PASS=你的口令
KS="${BOTCTL_KEYSTORE:-$PROJ/botctl.keystore}"
KS_PASS="${BOTCTL_KS_PASS:-}"
KEY_ALIAS="${BOTCTL_KEY_ALIAS:-botctl}"

if [ ! -f "$KS" ]; then
  if [ "${BOTCTL_NO_SIGN:-0}" = "1" ]; then
    OUT=$PROJ/BotCtl-$VER-unsigned.apk
    cp -f aligned.apk "$OUT"
    echo "  ⚠ 未提供 keystore，输出未签名包：$OUT"
    echo "    未签名 APK 不能直接安装，仅供检查产物"
    echo "BUILD_DONE $OUT"
    exit 0
  fi
  # 首次生成 keystore：没给口令就随机生成一个并打印出来，请自己保存
  if [ -z "$KS_PASS" ]; then
    KS_PASS=$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | cut -c1-20)
    echo "  ⚠ 已为本次 keystore 生成随机口令（只显示这一次，请保存）："
    echo "      $KS_PASS"
    echo "    以后构建：export BOTCTL_KS_PASS=$KS_PASS"
  fi
  keytool -genkeypair -keystore "$KS" -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=BotCtl, OU=BotCtl, O=BotCtl, L=NA, ST=NA, C=CN" >/dev/null 2>&1 \
    || die "keytool failed"
  echo "  keystore 已生成：$KS"
  echo "  ⚠ 这是临时密钥：CI 里每次都会换新密钥，装过旧版的设备必须先卸载"
else
  [ -n "$KS_PASS" ] || die "已有 keystore 但没给口令。请先 export BOTCTL_KS_PASS=你的口令"
  echo "  reusing keystore $KS"
fi

OUT=$PROJ/BotCtl-$VER.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out "$OUT" aligned.apk || die "apksigner failed"
echo "  signed: $OUT ($(stat -c %s "$OUT") bytes)"

echo
echo "===== 6) verify ====="
"$BT/apksigner" verify --verbose "$OUT" | head -6 | sed 's/^/  /'
"$BT/aapt2" dump badging "$OUT" | grep -E "package|launchable-activity|sdkVersion|application-label" | head -6 | sed 's/^/  /'
echo
echo "BUILD_DONE $OUT"
