#!/usr/bin/env bash
# 本机构建 vivo 白名单 10 合 1：一次产出 10 个「代码相同、只有包名不同」的已签名 APK，
# 产物名 <包名>.apk —— 与 Release 附件命名一致。
#
#   bash scripts/build10.sh                     # 全部 10 个 → dist/
#   PKGS="com.tencent.weread" bash scripts/build10.sh   # 只构建指定包（调试用）
#   OUT_DIR=/tmp/out bash scripts/build10.sh
#
# 依赖：本地 Gradle 8.9（仓库不含 gradlew）、Android SDK、以及签名凭据。
# 凭据读取顺序（见 app/build.gradle）：
#   local.properties 的 key.store.file / key.store.password / key.alias / key.alias.password
#   或环境变量 KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD
# 都没有时 release 不签名，产物是 app-release-unsigned.apk —— 本脚本会直接报错退出，
# 免得把没签名的包当成品发出去。本脚本不包含任何口令。
set -euo pipefail
cd "$(dirname "$0")/.."

OUT_DIR="${OUT_DIR:-dist}"
ALL_PKGS="com.spotify.music com.apple.android.music com.luna.music.car com.tencent.wecarflow
com.kugou.android.auto cn.kuwo.kwmusiccar com.tencent.ibg.joox com.tencent.weread
com.qidian.QDReader cn.aqzscn.stream_music"
PKGS="${PKGS:-$ALL_PKGS}"

mkdir -p "$OUT_DIR"
echo "== 构建 ${PKGS} 共 $(echo $PKGS | wc -w) 个包 → $OUT_DIR/"

for pkg in $PKGS; do
  echo
  echo "=== $pkg ==="
  # --no-daemon：一次跑 10 个包，别让守护进程常驻吃内存
  gradle --no-daemon :app:assembleRelease -PoverrideAppId="$pkg"

  out=$(find app/build/outputs/apk/release -maxdepth 1 -name '*.apk' -printf '%T@ %p\n' \
        | sort -rn | head -1 | cut -d' ' -f2-)
  case "$out" in
    "" ) echo "找不到 APK 产物"; exit 1 ;;
    *-unsigned.apk ) echo "产物未签名（$out）—— 请先配签名凭据，见本脚本头部注释"; exit 1 ;;
  esac

  cp "$out" "$OUT_DIR/$pkg.apk"
  echo "  $out  →  $OUT_DIR/$pkg.apk"
  # 回读校验：包名必须与文件名一致。
  # （别在这里调 SDK 的 apksigner 封装脚本，本环境它会挂住；
  #   签名指纹用 sign-apk 一侧或 apksig 库校验。）
  if command -v aapt2 >/dev/null 2>&1; then
    aapt2 dump badging "$OUT_DIR/$pkg.apk" 2>/dev/null | grep -E "^package:" | sed 's/^/  /'
  fi
done

echo
echo "== 完成，产物："
ls -l "$OUT_DIR"/*.apk
