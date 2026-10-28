#!/system/bin/sh
# =============================================================================
# Cirno Recycle+ post-fs-data
#
# 在 system_server 启动之前把配置目录建好, 避免首次启动时 system_server
# 因为 SELinux / 目录权限问题创建失败.
# =============================================================================

MODDIR=${0%/*}
[ -f "$MODDIR/disable" ] && exit 0

CONFIG_DIR=/data/misc/CirnoRecycle
mkdir -p "$CONFIG_DIR/log" 2>/dev/null
chown system:system "$CONFIG_DIR" "$CONFIG_DIR/log" 2>/dev/null
chmod 0770 "$CONFIG_DIR" "$CONFIG_DIR/log" 2>/dev/null
chcon u:object_r:system_data_file:s0 "$CONFIG_DIR" 2>/dev/null

exit 0
