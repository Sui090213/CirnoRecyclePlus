#!/system/bin/sh
# =============================================================================
# 开机后补一次禁用. 安装时已经处理过, 这里主要覆盖:
#   - recovery 刷入 (安装时 PackageManager 还没起来);
#   - 系统 OTA 被系统重新 enable 的情况.
# =============================================================================

MODDIR=${0%/*}

# 等 system_server / PackageManager 就绪, 最多等 120 秒, 避免开机脚本永久挂住.
i=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$i" -lt 60 ]; do
    sleep 2
    i=$((i + 1))
done

if [ "$(getprop sys.boot_completed)" = "1" ] && [ -f "$MODDIR/ota_block.sh" ]; then
    sh "$MODDIR/ota_block.sh" apply >/dev/null 2>&1
fi

exit 0
