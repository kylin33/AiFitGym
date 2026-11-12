#!/usr/bin/env bash
# 本地打 release 包并（可选）上传到 GitHub Release。
#
#   tools/release.sh              # 只出包，APK 落在 build/release/
#   tools/release.sh v1.0.0       # 出包 + 用 gh 创建 Release 并上传
#
# 需要：本机有 keystore.properties（见 keystore.properties.example）。
# 上传需要 gh CLI（winget install GitHub.cli），且已 gh auth login。

set -euo pipefail

cd "$(dirname "$0")/.."

TAG="${1:-}"
OUT_DIR="build/release"
VERSION_NAME="$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts | head -1)"
VERSION_CODE="$(grep -oP 'versionCode\s*=\s*\K[0-9]+' app/build.gradle.kts | head -1)"

if [ ! -f keystore.properties ]; then
    echo "✗ 缺少 keystore.properties —— release 包会没有签名，装不上也发不了。"
    echo "  先执行：cp keystore.properties.example keystore.properties 再填写四项。"
    exit 1
fi

echo "→ 构建 v${VERSION_NAME} (versionCode ${VERSION_CODE})"
./gradlew :app:assembleRelease --console=plain

APK="$(ls app/build/outputs/apk/release/*.apk | head -1)"
mkdir -p "$OUT_DIR"
TARGET="${OUT_DIR}/FitGym-${VERSION_NAME}.apk"
cp "$APK" "$TARGET"

SIZE="$(du -h "$TARGET" | cut -f1)"
echo ""
echo "✓ ${TARGET}  (${SIZE})"
sha256sum "$TARGET" | tee "${TARGET}.sha256"

if [ -z "$TAG" ]; then
    echo ""
    echo "没有传 tag，只出包。要发布就再来一次：tools/release.sh v${VERSION_NAME}"
    exit 0
fi

if ! command -v gh >/dev/null 2>&1; then
    echo ""
    echo "✗ 没装 gh CLI，无法自动发版。两种办法："
    echo "  1. winget install GitHub.cli && gh auth login  之后重跑"
    echo "  2. 打开 https://github.com/kylin33/FitGym/releases/new?tag=${TAG}"
    echo "     手动上传 ${TARGET}"
    exit 1
fi

echo ""
echo "→ 创建 Release ${TAG}"
gh release create "$TAG" "$TARGET" \
    --title "FitGym ${TAG}" \
    --notes "安装包：$(basename "$TARGET")（已签名，可直接覆盖安装升级）"

echo "✓ 完成：$(gh release view "$TAG" --json url -q .url)"
