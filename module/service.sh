#!/system/bin/sh
# =============================================================================
# Cirno Recycle+ 开机脚本
#
# 1. 等系统启动完成;
# 2. 如果刷入时没能安装 APK (recovery 刷入 / PackageManager 未就绪), 这里重试;
# 3. 修复配置目录权限, 打印 LSPosed 状态到 install.log.
# =============================================================================

MODDIR=${0%/*}
LOG="$MODDIR/install.log"
PKG=com.flyingr.cirnorecycle

log() {
    echo "$(date '+%m-%d %H:%M:%S' 2>/dev/null) $*" >>"$LOG"
}

[ -f "$MODDIR/disable" ] && exit 0

# 等开机完成, 最多约 3 分钟
i=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 60 ]; do
    i=$((i + 1))
    sleep 3
done
sleep 3

# 配置目录 (post-fs-data.sh 里已经建过, 这里兜底)
CONFIG_DIR=/data/misc/CirnoRecycle
mkdir -p "$CONFIG_DIR/log" 2>/dev/null
chown system:system "$CONFIG_DIR" "$CONFIG_DIR/log" 2>/dev/null
chmod 0770 "$CONFIG_DIR" "$CONFIG_DIR/log" 2>/dev/null

# APK 兜底安装
APK="$MODDIR/apk/CirnoRecyclePlus.apk"
if ! pm list packages 2>/dev/null | grep -q "package:$PKG"; then
    if [ -f "$APK" ]; then
        log "APK 尚未安装, 开始重试"
        for i in 1 2 3 4 5; do
            if pm install -r -d -g --user 0 "$APK" >>"$LOG" 2>&1 ||
                pm install -r -d -g "$APK" >>"$LOG" 2>&1; then
                break
            fi
            sleep 6
        done
        if pm list packages 2>/dev/null | grep -q "package:$PKG"; then
            log "APK 安装成功"
            rm -rf "$MODDIR/apk" "$MODDIR/.apk_pending"
        else
            log "APK 安装仍然失败, 请手动安装模块目录里的 apk/CirnoRecyclePlus.apk"
        fi
    else
        log "配套 APK 未安装, 且模块目录里没有 apk 文件; 请重新刷入模块 zip, 或从 zip 里取出 apk/CirnoRecyclePlus.apk 手动安装"
    fi
else
    rm -f "$MODDIR/.apk_pending"
fi

# 记录 LSPosed 状态, 方便排查
if [ -d /data/adb/lspd ] || [ -d /data/adb/modules/zygisk_lsposed ] || [ -d /data/adb/modules/lsposed ]; then
    log "LSPosed: 已安装"
else
    log "LSPosed: 未检测到 (本模块需要 LSPosed 才能生效)"
fi

# 开机后再应用一次 millet 兼容模式: post-fs-data 时如果内核模块已经加载，
# rmmod 可能失败；这里等系统起来后再试一次，并记录结果.
if [ -f "$MODDIR/millet_ctl.sh" ] && [ -f "$MODDIR/millet_disabled" ]; then
    if sh "$MODDIR/millet_ctl.sh" boot >>"$LOG" 2>&1; then
        log "millet 兼容模式: 已应用 (重启后完全生效)"
    else
        log "millet 兼容模式: 应用失败，已保留备份，下次开机会重试"
    fi
fi

log "service.sh 执行完毕"
exit 0
