#!/usr/bin/env bash
# 下载上游依赖（不进仓库的两个 jar），并做 SHA256 校验。
#
# 用法：
#   bash scripts/fetch-upstream.sh              # 下载到 ./dist
#   bash scripts/fetch-upstream.sh --mirror     # 走 gh-proxy 加速（国内服务器建议）
#
# 校验失败会直接退出，避免把坏包部署上去。
set -uo pipefail

ZP_VER="3.7.3%2Bjava.1.21.4"
ZP_NAME="ZenithProxy.jar"
ZP_SHA="3B57CA7980D78C0B1F6B1ADDF9EF5C55EDFE9B221144C9C9A69889CCAFC986F0"

WU_VER="v1.4.0"
WU_NAME="ZenithProxy-WebUI-1.4.0.jar"
WU_SHA="7443682AC97CF8BD60AD57EF7CD85956144D8846FDAF1CD837B39193997E9C20"

ZP_URL="https://github.com/rfresh2/ZenithProxy/releases/download/${ZP_VER}/${ZP_NAME}"
WU_URL="https://github.com/wxh1024/ZenithProxy-WebUI/releases/download/${WU_VER}/${WU_NAME}"

OUT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/dist"
MIRROR=0
for a in "$@"; do case "$a" in --mirror) MIRROR=1 ;; esac; done

mkdir -p "$OUT_DIR"
cd "$OUT_DIR" || exit 1

fetch() {  # fetch <url> <file>
  local url="$1" out="$2"
  if [ "$MIRROR" = "1" ]; then
    url="https://gh-proxy.com/${url}"
    echo "  使用加速镜像：gh-proxy.com"
  fi
  echo "  下载 $out"
  curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 -o "$out.part" "$url" || return 1
  mv -f "$out.part" "$out"
}

check() {  # check <file> <sha>
  local sum
  sum=$(sha256sum "$1" | awk '{print toupper($1)}')
  if [ "$sum" = "$2" ]; then
    echo "  ✓ $1 校验通过"
  else
    echo "  ✗ $1 校验失败" >&2
    echo "    期望 $2" >&2
    echo "    实际 $sum" >&2
    return 1
  fi
}

echo "==> 获取 ZenithProxy $ZP_VER"
if [ -f "$ZP_NAME" ] && check "$ZP_NAME" "$ZP_SHA" 2>/dev/null; then
  echo "  已存在且校验通过，跳过"
else
  fetch "$ZP_URL" "$ZP_NAME" || { echo "下载失败：$ZP_URL" >&2; exit 1; }
  check "$ZP_NAME" "$ZP_SHA" || exit 1
fi

echo "==> 获取 ZenithProxy-WebUI $WU_VER"
if [ -f "$WU_NAME" ] && check "$WU_NAME" "$WU_SHA" 2>/dev/null; then
  echo "  已存在且校验通过，跳过"
else
  fetch "$WU_URL" "$WU_NAME" || { echo "下载失败：$WU_URL" >&2; exit 1; }
  check "$WU_NAME" "$WU_SHA" || exit 1
fi

echo
echo "完成，文件在：$OUT_DIR"
echo "下一步：把 dist/ 里两个 jar 放到部署脚本同级，然后"
echo "  sudo bash server/deploy_zenith.sh --server 你的服务器地址"
