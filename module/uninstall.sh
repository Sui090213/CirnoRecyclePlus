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

# 能卸载就卸载; 卸载不了也没关系, /data/adb/service.d 里的清理脚本会在
# 下次开机后检测到模块目录不存在, 再卸载一次 App 并删除自己.
# 注意: 这里不要删除那个清理脚本, 否则 pm 不可用时 App 就永远留在手机上了.
pm uninstall "$PKG" >/dev/null 2>&1

exit 0
