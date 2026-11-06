#!/system/bin/sh
# =============================================================================
# Cirno ColorOS 17 系统更新禁用 - 安装脚本
#
# 只做一件事: 把 ColorOS 的 OTA 客户端设为 disabled-user. 不修改 system 分区,
# 卸载模块时 uninstall.sh 会恢复. 这是一个独立可选模块, 和 Cirno Recycle+ 主模块无关.
# =============================================================================

SKIPUNZIP=0

command -v ui_print >/dev/null 2>&1 || ui_print() { echo "$1"; }
command -v abort >/dev/null 2>&1 || abort() { echo "$1"; exit 1; }

ui_print "********************************************"
ui_print "- Cirno ColorOS 17 系统更新禁用 v1.4.0"
ui_print "- 作者: flyingr  ·  独立可选模块"
ui_print "********************************************"

if [ ! -f "$MODPATH/ota_block.sh" ]; then
    abort "! 模块文件不完整 (ota_block.sh 缺失), 请重新下载 zip"
fi

ROM_NAME="$(getprop ro.build.version.oplusrom)$(getprop ro.build.version.opporom)$(getprop ro.build.version.realmeui)$(getprop ro.build.version.oxygenos)"
BRAND="$(getprop ro.product.brand)"
ui_print "- 设备: ${BRAND:-unknown}  ROM: ${ROM_NAME:-unknown}"

case "${ROM_NAME}${BRAND}" in
*ColorOS* | *coloros* | *OxygenOS* | *oxygenos* | *realme* | *Realme* | *OPPO* | *oppo* | *OnePlus* | *oneplus* | *oplus* | *OPlus*)
    ui_print "- 检测到 OPPO 系 ROM"
    ;;
*)
    ui_print "! 没有识别到 ColorOS / OxygenOS / realme UI"
    ui_print "! 本模块只会尝试禁用已知 OTA 包; 非 OPPO 系设备刷入不会生效, 可直接卸载"
    ;;
esac

if [ "$BOOTMODE" = "true" ]; then
    ui_print "- 正在禁用系统更新包 ..."
    sh "$MODPATH/ota_block.sh" apply
else
    ui_print "- 当前不是开机状态 (recovery 刷入), 开机后由 service.sh 自动禁用"
fi

# ---------------------------------------------------------------------------
# 保留一份恢复脚本 + 开机兜底:
# 如果模块在 recovery / pm 不可用时被卸载, uninstall.sh 无法恢复,
# 下次开机的 service.d 脚本会检测模块目录已消失并自动 enable 更新包.
# ---------------------------------------------------------------------------
STATE_DIR=/data/adb/cirno_coloros_ota
mkdir -p "$STATE_DIR" /data/adb/service.d 2>/dev/null
cp -f "$MODPATH/ota_block.sh" "$STATE_DIR/ota_block.sh" 2>/dev/null
chmod 0755 "$STATE_DIR/ota_block.sh" 2>/dev/null

GUARD=/data/adb/service.d/cirno_coloros_ota_guard.sh
cat >"$GUARD" <<'GUARD_EOF'
#!/system/bin/sh
MOD=/data/adb/modules/cirno_coloros17_ota_block
if [ ! -d "$MOD" ] && [ -f /data/adb/cirno_coloros_ota/ota_block.sh ]; then
    sh /data/adb/cirno_coloros_ota/ota_block.sh restore
    rm -f /data/adb/service.d/cirno_coloros_ota_guard.sh
fi
exit 0
GUARD_EOF
chmod 0755 "$GUARD" 2>/dev/null

ui_print "- 卸载模块会自动恢复更新包"
ui_print "- 完成, 重启后生效"
