#!/system/bin/sh
# =============================================================================
# 卸载时恢复系统更新包.
#
# 如果此时 PackageManager 还没就绪 (例如 recovery 里移除模块), 恢复会失败;
# customize.sh 已经写了一个 /data/adb/service.d 的兜底脚本, 下次开机会再恢复一次.
# =============================================================================

MODDIR=${0%/*}

if [ -f "$MODDIR/ota_block.sh" ]; then
    sh "$MODDIR/ota_block.sh" restore >/dev/null 2>&1
fi

exit 0
