#!/usr/bin/env bash
# E2 镜像站部署（可选）：把"版本三件套"发到静态镜像站，
# 供 APK 内多源检测/下载优先使用（UpdateChecker.fetchFromMirror 对齐）。
#
# 站点结构：
#   [MIRROR_BASE]/version.txt                # 版本号（如 1.9，无 v 前缀）+ 可选一行 sha256=<hex>（哈希闸兜底）
#   [MIRROR_BASE]/notes.txt                  # 更新日志（GitHub Release body 原文）
#   [MIRROR_BASE]/lofiroom-latest.apk        # 最新 APK（与 release 固定名资产同名）
#   [MIRROR_BASE]/lofiroom-latest.apk.sha256 # APK 哈希旁文件（sha256sum 格式，UpdateChecker 哈希闸优先读它）
#   [MIRROR_BASE]/meta.txt                   # versionCode + APK 字节数（官网下载页版本明细行动态刷新源）
#
# 用法：tools/deploy-site.sh v1.9
# 上传顺序：先 APK/sha256/notes，最后 version.txt —— 避免 version.txt 先行指向未就绪的新包。
set -euo pipefail

TAG="${1:?用法: tools/deploy-site.sh v1.9}"
VERSION="${TAG#v}"

# ── 脏树前置校验：脏树（未提交改动）出的本地包 ≠ CI 包，优先上传本地 APK 会把未入库
#    的脏资源（如误删/重复壁纸）打进 R2 镜像 —— REVIEW-2026-11-20.md P1-1 教训。
#    要带改动部署，先提交或 stash；确要强制跳过：ALLOW_DIRTY=1 tools/deploy-site.sh vX.Y
if [[ "${ALLOW_DIRTY:-0}" == "1" ]]; then
  echo "⚠ ALLOW_DIRTY=1：跳过脏树校验，本地脏包/杂散资源可能进 R2 镜像" >&2
  command -v git >/dev/null 2>&1 && git status --short >&2
fi
if [[ "${ALLOW_DIRTY:-0}" != "1" ]] && command -v git >/dev/null 2>&1 && git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  # 已跟踪改动 + 未跟踪新文件（git status --porcelain 皆可见；原 git diff 只看已跟踪）
  DIRTY="$(git status --porcelain)"
  # 被 .gitignore 忽略却躺在 assets/ 里的残留（如 iCloud "* 2.*" 副本）：git diff/status 看不见，
  # 但 AGP assets 打包照单全收 → 本机包≠CI 包 → 污染 R2（REVIEW-2026-11-20.md P1-1 教训）
  IGNORED_ASSETS="$(git ls-files --others --ignored --exclude-standard app/src/main/assets 2>/dev/null)"
  if [[ -n "$DIRTY" || -n "$IGNORED_ASSETS" ]]; then
    echo "工作树有未提交/未跟踪/被忽略残留：先提交/stash/清理再部署，或 ALLOW_DIRTY=1 强制跳过" >&2
    git status --short >&2
    if [[ -n "$IGNORED_ASSETS" ]]; then
      echo "── assets/ 被忽略残留（AGP 会打进包，务必清掉）：" >&2
      printf '%s\n' "$IGNORED_ASSETS" >&2
    fi
    exit 1
  fi
fi

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

# 1. notes.txt：Release body；无 gh CLI 时留空（APK 内 notes 可为空）
if command -v gh >/dev/null 2>&1; then
  # set -e 下 gh 对「还没有该 tag 的 Release」会返回非 0，直接把脚本打断在部署前一步；
  # 更新日志不是必需项，取不到就留空继续（只有真正的网络/权限错误才值得看日志）。
  gh release view "$TAG" --json body -q .body > "$WORK/notes.txt" 2>/dev/null \
    || { echo "gh release view $TAG 无结果：notes.txt 留空，继续部署" >&2; : > "$WORK/notes.txt"; }
else
  echo "gh 未安装：$WORK/notes.txt 留空（可手工补更新日志）" >&2
  : > "$WORK/notes.txt"
fi

# 2. APK：优先本地 release 产物，其次 GitHub Release 固定名直链
if [[ -f app/build/outputs/apk/release/app-release.apk ]]; then
  cp app/build/outputs/apk/release/app-release.apk "$WORK/lofiroom-latest.apk"
else
  need_cmd curl
  curl -fL -o "$WORK/lofiroom-latest.apk" \
    "https://github.com/ycyx1994/lofi-room/releases/latest/download/lofiroom-latest.apk"
fi

# 3. 哈希旁文件 + version.txt 哈希行（与 UpdateChecker.parseSha256 / SHA256_LINE_RE 对齐：
#    旁文件 sha256sum 格式 "<hex>  lofiroom-latest.apk"，version.txt 一行 "sha256=<hex>"。
#    macOS 自带 shasum 不带 sha256sum，两者都尝试）
sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'
  else echo "缺少依赖：sha256sum 或 shasum（生成 APK 哈希用）" >&2; exit 1; fi
}
APK_SHA256="$(sha256_of "$WORK/lofiroom-latest.apk")"
printf '%s  lofiroom-latest.apk\n' "$APK_SHA256" > "$WORK/lofiroom-latest.apk.sha256"

# 4. version.txt：唯一可信版本标识（APK 内 parseVersion 白名单只收点分数字），
#    附一行哈希（splitVersionAndHash 摘出后交哈希闸；多条哈希行按脏数据整页拒收，故只写一条）
printf '%s\nsha256=%s\n' "$VERSION" "$APK_SHA256" > "$WORK/version.txt"

# 5. meta.txt：官网下载页版本明细行的动态刷新源（docs/index.html vermeta），
#    versionCode 取自 app/build.gradle.kts，size 取实际 APK 字节数；取不到的字段就不写行
META_VC="$(sed -nE 's/.*versionCode[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' app/build.gradle.kts | head -n1)"
META_SIZE="$(wc -c < "$WORK/lofiroom-latest.apk" | tr -d '[:space:]')"
: > "$WORK/meta.txt"
if [[ -n "$META_VC" ]]; then printf 'versionCode=%s\n' "$META_VC" >> "$WORK/meta.txt"; fi
if [[ -n "$META_SIZE" ]]; then printf 'size=%s\n' "$META_SIZE" >> "$WORK/meta.txt"; fi

# 6. 上传（APK/sha256/meta/notes 先行，version.txt 收尾）
case "$UPLOAD_METHOD" in
  rsync)
    rsync -av "$WORK/lofiroom-latest.apk" "$WORK/lofiroom-latest.apk.sha256" "$WORK/notes.txt" "$WORK/meta.txt" "$SSH_USER@$MIRROR_HOST:$MIRROR_DIR/"
    rsync -av "$WORK/version.txt" "$SSH_USER@$MIRROR_HOST:$MIRROR_DIR/"
    ;;
  scp)
    scp "$WORK/lofiroom-latest.apk" "$WORK/lofiroom-latest.apk.sha256" "$WORK/notes.txt" "$WORK/meta.txt" "$SSH_USER@$MIRROR_HOST:$MIRROR_DIR/"
    scp "$WORK/version.txt" "$SSH_USER@$MIRROR_HOST:$MIRROR_DIR/"
    ;;
  wrangler)
    # Cloudflare R2 公共桶（与 UpdateChecker.MIRROR_BASE 对齐）：
    # 桶名 lofiroom，对象键 lofiroom/{version.txt,notes.txt,meta.txt,lofiroom-latest.apk,lofiroom-latest.apk.sha256}
    BUCKET="${R2_BUCKET:-lofiroom}"
    # 与 :11 不变量对齐：APK/sha256/meta/notes 先行，version.txt 收尾（rsync/scp 分支同语义），
    # 避免 version.txt 先行指向未就绪的新包。
    for f in notes.txt meta.txt lofiroom-latest.apk lofiroom-latest.apk.sha256 version.txt; do
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
