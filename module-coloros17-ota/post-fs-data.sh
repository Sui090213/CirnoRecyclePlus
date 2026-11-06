#!/system/bin/sh
# =============================================================================
# 早期只创建状态目录, 不调用 pm (此时 system_server 还没起来).
# 真正的禁用由 customize.sh (开机安装时) 和 service.sh (开机后) 完成.
# =============================================================================

MODDIR=${0%/*}
mkdir -p /data/adb/cirno_coloros_ota 2>/dev/null
chmod 0700 /data/adb/cirno_coloros_ota 2>/dev/null

exit 0
