#!/usr/bin/env bash
# =============================================================================
# Cirno Recycle+ 模块打包脚本 (Linux / macOS / CI)
#
# 用法: bash scripts/package-module.sh [apk路径]
# 输出: dist/Cirno-Recycle-Plus-<version>.zip + dist/CHECKSUMS.txt
#
# 依赖: zip / unzip / sha256sum 或 shasum / apksigner (可选, 用于签名校验)
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

# 未签名的 APK 在设备上 pm install 必然失败, 提前拦截.
case "$APK" in
*unsigned.apk)
    echo "拒绝打包未签名 APK: $APK" >&2
    echo "请配置 signing/cirno-release.jks 后执行 assembleRelease" >&2
    exit 1
    ;;
esac

APKSIGNER="${APKSIGNER:-}"
if [ -z "$APKSIGNER" ] && [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/build-tools/36.0.0/apksigner" ]; then
    APKSIGNER="$ANDROID_HOME/build-tools/36.0.0/apksigner"
fi
if [ -z "$APKSIGNER" ] && command -v apksigner >/dev/null 2>&1; then
    APKSIGNER="$(command -v apksigner)"
fi
if [ -n "$APKSIGNER" ] && [ -x "$APKSIGNER" ]; then
    if ! "$APKSIGNER" verify "$APK" >/dev/null 2>&1; then
        echo "APK 签名校验失败, 拒绝打包: $APK" >&2
        exit 1
    fi
    echo "APK 签名校验通过"
else
    echo "警告: 没找到 apksigner, 跳过签名校验" >&2
fi

command -v zip >/dev/null 2>&1 || {
    echo "需要 zip 命令 (Debian/Ubuntu: apt install zip; macOS 自带)" >&2
    exit 1
}

VERSION="$(grep -E '^\s*version\s*=' "$MODULE_DIR/module.prop" | head -n1 | cut -d= -f2 | tr -d ' \r' | sed 's/^[vV]//')"
[ -n "$VERSION" ] || VERSION="1.4.0"

ZIP_NAME="Cirno-Recycle-Plus-v${VERSION}.zip"
ZIP_PATH="$OUTPUT_DIR/$ZIP_NAME"

STAGE="$REPO_ROOT/build/module-stage"
rm -rf "$STAGE"
mkdir -p "$STAGE" "$OUTPUT_DIR"
cp -a "$MODULE_DIR/." "$STAGE/"

mkdir -p "$STAGE/apk"
cp -f "$APK" "$STAGE/apk/CirnoRecyclePlus.apk"

# 统一换行符为 LF (tr 在 GNU / BSD / macOS 上都能用, 避免 GNU 专用 sed -i)
find "$STAGE" -type f \( -name '*.sh' -o -name '*.prop' -o -name '*.md' -o -name '*.rule' -o -name 'update-binary' -o -name 'updater-script' \) -print0 |
    while IFS= read -r -d '' file; do
        if grep -q $'\r' "$file" 2>/dev/null; then
            tr -d '\r' <"$file" >"$file.tmp" && mv -f "$file.tmp" "$file"
        fi
    done

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

if command -v sha256sum >/dev/null 2>&1; then
    HASH="$(sha256sum "$ZIP_PATH" | cut -d' ' -f1)"
else
    # macOS 自带 shasum
    HASH="$(shasum -a 256 "$ZIP_PATH" | cut -d' ' -f1)"
fi
echo "$HASH  $ZIP_NAME" >"$OUTPUT_DIR/CHECKSUMS.txt"

echo "============================================="
echo " 模块打包完成: $ZIP_PATH"
echo " 大小: $(du -h "$ZIP_PATH" | cut -f1)"
echo " SHA256: $HASH"
echo " APK 来源: $APK"
echo "============================================="

rm -rf "$STAGE"
