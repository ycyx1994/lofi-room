#!/usr/bin/env bash
# E2 镜像站部署（可选）：把"版本三件套"发到静态镜像站，
# 供 APK 内多源检测/下载优先使用（UpdateChecker.fetchFromMirror 对齐）。
#
# 站点结构：
#   [MIRROR_BASE]/version.txt          # 纯文本版本号，如 1.9（无 v 前缀、无空白）
#   [MIRROR_BASE]/notes.txt            # 更新日志（GitHub Release body 原文）
#   [MIRROR_BASE]/lofiroom-latest.apk  # 最新 APK（与 release 固定名资产同名）
#
# 用法：tools/deploy-site.sh v1.9
# 上传顺序：先 APK/notes，最后 version.txt —— 避免 version.txt 先行指向未就绪的新包。
set -euo pipefail

TAG="${1:?用法: tools/deploy-site.sh v1.9}"
VERSION="${TAG#v}"

# ── 配置（占位符，按实际镜像站替换；也可用环境变量覆盖） ──
MIRROR_HOST="${MIRROR_HOST:-[MIRROR_HOST]}"       # 静态站主机，如 cdn.example.com
MIRROR_DIR="${MIRROR_DIR:-/lofiroom}"             # 站点子路径，与 UpdateChecker.MIRROR_BASE 一致
# R2 对象键前缀 = 站点子路径去掉首尾斜杠（/lofiroom → lofiroom；"/" → 空）。
# 下方 wrangler 的参数格式是 <bucket>/<key>，所以键前缀不能再带前导斜杠，
# 否则会写出 "//lofiroom/version.txt" 这种取不到的对象键（原来的 unbound variable 修好后仍会踩这个坑）。
MIRROR_DIR_NOSLASH="${MIRROR_DIR#/}"
MIRROR_DIR_NOSLASH="${MIRROR_DIR_NOSLASH%/}"
SSH_USER="${SSH_USER:-[SSH_USER]}"
UPLOAD_METHOD="${UPLOAD_METHOD:-rsync}"           # rsync | scp | wrangler

# 依赖前置校验：只检查当前分支真正会用到的命令。缺依赖时给一句人话，
# 而不是 set -e 下 "command not found: rsync" 的半截退出。
need_cmd() {
  command -v "$1" >/dev/null 2>&1 \
    || { echo "缺少依赖：$1（UPLOAD_METHOD=$UPLOAD_METHOD 分支需要它，请先安装）" >&2; exit 1; }
}
case "$UPLOAD_METHOD" in
  rsync)    need_cmd rsync ;;
  scp)      need_cmd scp ;;
  wrangler) need_cmd npx ;;
esac

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# 1. version.txt：唯一可信版本标识（APK 内 parseVersion 白名单只收点分数字）
printf '%s' "$VERSION" > "$WORK/version.txt"

# 2. notes.txt：Release body；无 gh CLI 时留空（APK 内 notes 可为空）
if command -v gh >/dev/null 2>&1; then
  # set -e 下 gh 对「还没有该 tag 的 Release」会返回非 0，直接把脚本打断在部署前一步；
  # 更新日志不是必需项，取不到就留空继续（只有真正的网络/权限错误才值得看日志）。
  gh release view "$TAG" --json body -q .body > "$WORK/notes.txt" 2>/dev/null \
    || { echo "gh release view $TAG 无结果：notes.txt 留空，继续部署" >&2; : > "$WORK/notes.txt"; }
else
  echo "gh 未安装：$WORK/notes.txt 留空（可手工补更新日志）" >&2
  : > "$WORK/notes.txt"
fi

# 3. APK：优先本地 release 产物，其次 GitHub Release 固定名直链
if [[ -f app/build/outputs/apk/release/app-release.apk ]]; then
  cp app/build/outputs/apk/release/app-release.apk "$WORK/lofiroom-latest.apk"
else
  need_cmd curl
  curl -fL -o "$WORK/lofiroom-latest.apk" \
    "https://github.com/ycyx1994/lofi-room/releases/latest/download/lofiroom-latest.apk"
fi

# 4. 上传（APK+notes 先行，version.txt 收尾）
case "$UPLOAD_METHOD" in
  rsync)
    rsync -av "$WORK/lofiroom-latest.apk" "$WORK/notes.txt" "$SSH_USER@$MIRROR_HOST:$MIRROR_DIR/"
    rsync -av "$WORK/version.txt" "$SSH_USER@$MIRROR_HOST:$MIRROR_DIR/"
    ;;
  scp)
    scp "$WORK/lofiroom-latest.apk" "$WORK/notes.txt" "$SSH_USER@$MIRROR_HOST:$MIRROR_DIR/"
    scp "$WORK/version.txt" "$SSH_USER@$MIRROR_HOST:$MIRROR_DIR/"
    ;;
  wrangler)
    # Cloudflare R2 公共桶（与 UpdateChecker.MIRROR_BASE 对齐）：
    # 桶名 lofiroom，对象键 lofiroom/{version.txt,notes.txt,lofiroom-latest.apk}
    BUCKET="${R2_BUCKET:-lofiroom}"
    # 与 :11 不变量对齐：APK/notes 先行，version.txt 收尾（rsync/scp 分支同语义），
    # 避免 version.txt 先行指向未就绪的新包。
    for f in notes.txt lofiroom-latest.apk version.txt; do
      npx wrangler r2 object put "$BUCKET/${MIRROR_DIR_NOSLASH:+$MIRROR_DIR_NOSLASH/}$f" \
        --file "$WORK/$f" --remote
    done
    ;;
  *)
    echo "未知 UPLOAD_METHOD: $UPLOAD_METHOD（可选 rsync/scp/wrangler）" >&2
    exit 1
    ;;
esac

echo "已部署 $TAG → https://$MIRROR_HOST$MIRROR_DIR/  version.txt=$(cat "$WORK/version.txt")"
