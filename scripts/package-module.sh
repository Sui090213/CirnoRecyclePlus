#!/usr/bin/env bash
# =============================================================================
# Cirno Recycle+ 模块打包脚本 (Linux / macOS / CI)
#
# 用法: bash scripts/package-module.sh [apk路径]
# 输出: dist/Cirno-Recycle-Plus-<version>.zip + dist/CHECKSUMS.txt
# =============================================================================

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE_DIR="$REPO_ROOT/module"
OUTPUT_DIR="${OUTPUT_DIR:-$REPO_ROOT/dist}"

APK="${1:-}"
if [ -z "$APK" ]; then
    for candidate in \
        "$REPO_ROOT/app/build/outputs/apk/release/app-release.apk" \
        "$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk" \
        "$REPO_ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"; do
        if [ -f "$candidate" ]; then
            APK="$candidate"
            break
        fi
    done
fi

if [ -z "$APK" ] || [ ! -f "$APK" ]; then
    echo "找不到 APK: 请先 ./gradlew assembleRelease 或用第一个参数指定 APK 路径" >&2
    exit 1
fi

VERSION="$(grep -E '^\s*version\s*=' "$MODULE_DIR/module.prop" | head -n1 | cut -d= -f2 | tr -d ' \r' | sed 's/^[vV]//')"
[ -n "$VERSION" ] || VERSION="1.0.0"

ZIP_NAME="Cirno-Recycle-Plus-v${VERSION}.zip"
ZIP_PATH="$OUTPUT_DIR/$ZIP_NAME"

STAGE="$REPO_ROOT/build/module-stage"
rm -rf "$STAGE"
mkdir -p "$STAGE" "$OUTPUT_DIR"
cp -a "$MODULE_DIR/." "$STAGE/"

mkdir -p "$STAGE/apk"
cp -f "$APK" "$STAGE/apk/CirnoRecyclePlus.apk"

# 统一换行符为 LF
find "$STAGE" -type f \( -name '*.sh' -o -name '*.prop' -o -name '*.md' -o -name '*.rule' -o -name 'update-binary' -o -name 'updater-script' \) -print0 |
    xargs -0 -r sed -i 's/\r$//'

# 打包: 在暂存目录内执行, 保证 zip 根目录就是模块文件
rm -f "$ZIP_PATH"
(
    cd "$STAGE"
    zip -r -9 -q "$ZIP_PATH" .
)

# 结构校验
for required in module.prop customize.sh service.sh post-fs-data.sh action.sh uninstall.sh \
    apk/CirnoRecyclePlus.apk META-INF/com/google/android/update-binary \
    META-INF/com/google/android/updater-script; do
    if ! unzip -l "$ZIP_PATH" | grep -q " $required\$"; then
        echo "打包校验失败: zip 里缺少 $required" >&2
        exit 1
    fi
done

HASH="$(sha256sum "$ZIP_PATH" | cut -d' ' -f1)"
echo "$HASH  $ZIP_NAME" >"$OUTPUT_DIR/CHECKSUMS.txt"

echo "============================================="
echo " 模块打包完成: $ZIP_PATH"
echo " 大小: $(du -h "$ZIP_PATH" | cut -f1)"
echo " SHA256: $HASH"
echo " APK 来源: $APK"
echo "============================================="

rm -rf "$STAGE"
