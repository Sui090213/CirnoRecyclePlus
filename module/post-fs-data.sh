#!/system/bin/sh
# =============================================================================
# Cirno Recycle+ post-fs-data
#
# 在 system_server 启动之前把配置目录建好, 避免首次启动时 system_server
# 因为 SELinux / 目录权限问题创建失败.
# =============================================================================

MODDIR=${0%/*}
[ -f "$MODDIR/disable" ] && {
    # 模块被关闭时恢复 millet，避免用户停用模块后系统冻结仍然被关着.
    [ -f "$MODDIR/millet_ctl.sh" ] && sh "$MODDIR/millet_ctl.sh" restore-quiet >/dev/null 2>&1
    exit 0
}

CONFIG_DIR=/data/misc/CirnoRecycle
mkdir -p "$CONFIG_DIR/log" 2>/dev/null
chown system:system "$CONFIG_DIR" "$CONFIG_DIR/log" 2>/dev/null
chmod 0770 "$CONFIG_DIR" "$CONFIG_DIR/log" 2>/dev/null
chcon u:object_r:system_data_file:s0 "$CONFIG_DIR" 2>/dev/null

# 如果安装时启用了 millet 兼容模式，趁 system_server 还没起来再关一次属性、
# 尝试卸载已经加载的 millet_core，减少开机后两套冻结器抢进程.
[ -f "$MODDIR/millet_ctl.sh" ] && sh "$MODDIR/millet_ctl.sh" boot >/dev/null 2>&1

exit 0
