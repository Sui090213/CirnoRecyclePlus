#!/system/bin/sh
# =============================================================================
# Cirno Recycle+ 卸载脚本
#
# 模块被删除时执行. 运行时机很早 (PackageManager 通常还没起来), 所以这里只做
# 立即能做的事; App 的卸载由 service.d 里的清理脚本在开机完成后处理
# (见 customize.sh 生成的 /data/adb/service.d/cirno_recycle_plus_cleanup.sh).
#
# 配置目录 /data/misc/CirnoRecycle 默认保留, 方便重装后继续用; 需要彻底清理请手动:
#   rm -rf /data/misc/CirnoRecycle
# =============================================================================

MODDIR=${0%/*}
PKG=com.flyingr.cirnorecycle

rm -f "$MODDIR/install.log"
rm -f "/data/adb/service.d/cirno_recycle_plus_cleanup.sh"

# 能卸载就卸载, 卸载不了就留给 service.d 的清理脚本.
pm uninstall "$PKG" >/dev/null 2>&1

exit 0
