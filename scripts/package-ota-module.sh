#!/usr/bin/env bash
# =============================================================================
# 打包独立的 ColorOS 17 系统更新禁用模块
#
# 用法: bash scripts/package-ota-module.sh
# 输出: dist/Cirno-ColorOS17-OTA-Disabler-v<version>.zip
#       dist/CHECKSUMS-ota.txt
# =============================================================================

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE_DIR="$REPO_ROOT/module-coloros17-ota"
OUTPUT_DIR="${OUTPUT_DIR:-$REPO_ROOT/dist}"

if [ ! -f "$MODULE_DIR/module.prop" ]; then
    echo "找不到 module-coloros17-ota/module.prop" >&2
    exit 1
fi

command -v zip >/dev/null 2>&1 || {
    echo "需要 zip 命令 (Debian/Ubuntu: apt install zip; macOS 自带)" >&2
    exit 1
}

VERSION="$(grep -E '^\s*version\s*=' "$MODULE_DIR/module.prop" | head -n1 | cut -d= -f2 | tr -d ' \r' | sed 's/^[vV]//')"
[ -n "$VERSION" ] || VERSION="1.4.0"

ZIP_NAME="Cirno-ColorOS17-OTA-Disabler-v${VERSION}.zip"
ZIP_PATH="$OUTPUT_DIR/$ZIP_NAME"

STAGE="$REPO_ROOT/build/ota-module-stage"
rm -rf "$STAGE"
mkdir -p "$STAGE" "$OUTPUT_DIR"
cp -a "$MODULE_DIR/." "$STAGE/"

# 统一 LF, 去掉 BOM / CRLF; shell 脚本带 CRLF 会在设备上解析失败.
find "$STAGE" -type f \( -name '*.sh' -o -name '*.prop' -o -name '*.md' -o -name 'update-binary' -o -name 'updater-script' \) -print0 |
    while IFS= read -r -d '' file; do
        if grep -q $'\r' "$file" 2>/dev/null; then
            tr -d '\r' <"$file" >"$file.tmp" && mv -f "$file.tmp" "$file"
        fi
    done

rm -f "$ZIP_PATH"
(
    cd "$STAGE"
    zip -r -9 -q "$ZIP_PATH" .
)

for required in module.prop customize.sh service.sh post-fs-data.sh action.sh uninstall.sh ota_block.sh \
    META-INF/com/google/android/update-binary META-INF/com/google/android/updater-script; do
    if ! unzip -l "$ZIP_PATH" | grep -q " $required\$"; then
        echo "打包校验失败: zip 里缺少 $required" >&2
        exit 1
    fi
done

if command -v sha256sum >/dev/null 2>&1; then
    HASH="$(sha256sum "$ZIP_PATH" | cut -d' ' -f1)"
else
    HASH="$(shasum -a 256 "$ZIP_PATH" | cut -d' ' -f1)"
fi
echo "$HASH  $ZIP_NAME" >"$OUTPUT_DIR/CHECKSUMS-ota.txt"

echo "============================================="
echo " OTA 模块打包完成: $ZIP_PATH"
echo " 大小: $(du -h "$ZIP_PATH" | cut -f1)"
echo " SHA256: $HASH"
echo "============================================="

rm -rf "$STAGE"
