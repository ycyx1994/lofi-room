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
SSH_USER="${SSH_USER:-[SSH_USER]}"
UPLOAD_METHOD="${UPLOAD_METHOD:-rsync}"           # rsync | scp | wrangler

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# 1. version.txt：唯一可信版本标识（APK 内 parseVersion 白名单只收点分数字）
printf '%s' "$VERSION" > "$WORK/version.txt"

# 2. notes.txt：Release body；无 gh CLI 时留空（APK 内 notes 可为空）
if command -v gh >/dev/null 2>&1; then
  gh release view "$TAG" --json body -q .body > "$WORK/notes.txt"
else
  echo "gh 未安装：$WORK/notes.txt 留空（可手工补更新日志）" >&2
  : > "$WORK/notes.txt"
fi

# 3. APK：优先本地 release 产物，其次 GitHub Release 固定名直链
if [[ -f app/build/outputs/apk/release/app-release.apk ]]; then
  cp app/build/outputs/apk/release/app-release.apk "$WORK/lofiroom-latest.apk"
else
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
    # Cloudflare Pages 直传示例（[PAGES_PROJECT] 替换为实际项目名）
    npx wrangler pages deploy "$WORK" --project-name "[PAGES_PROJECT]" --branch main
    ;;
  *)
    echo "未知 UPLOAD_METHOD: $UPLOAD_METHOD（可选 rsync/scp/wrangler）" >&2
    exit 1
    ;;
esac

echo "已部署 $TAG → https://$MIRROR_HOST$MIRROR_DIR/  version.txt=$(cat "$WORK/version.txt")"
