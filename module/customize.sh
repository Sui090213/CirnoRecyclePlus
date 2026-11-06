#!/system/bin/sh
# =============================================================================
# Cirno Recycle+ 安装脚本 (Magisk / KernelSU / APatch 通用)
#
# 刷入时做四件事:
#   1. 检查 Android 版本与模块文件完整性;
#   2. 自动安装模块配套 APK (com.flyingr.cirnorecycle);
#   3. 创建配置目录 /data/misc/CirnoRecycle 并给出 LSPosed 启用提示;
#   4. 小米 / HyperOS 上检测 millet（Greeze），只在检测到运行中的 millet 时自动启用兼容模式.
#
# 注意: 这是一个 LSPosed 模块的配套 Magisk 模块, APK 本身才是主体;
# 刷入后还需要在 LSPosed 里把 "Cirno Recycle+" 启用并重启.
# =============================================================================

SKIPUNZIP=0

PKG=com.flyingr.cirnorecycle
MODULE_ID=cirno_recycle_plus

# ---------------------------------------------------------------------------
# 兼容性兜底: 极少数 root 方案 (或 KernelSU / APatch 的旧版本) 不会注入 Magisk 的
# ui_print / abort / set_perm 等函数, 这里补一份最小实现, 保证安装脚本不会中途报错.
# ---------------------------------------------------------------------------
command -v ui_print >/dev/null 2>&1 || ui_print() { echo "$1"; }
command -v abort >/dev/null 2>&1 || abort() { echo "$1"; exit 1; }
command -v set_perm >/dev/null 2>&1 || set_perm() { :; }
command -v set_perm_recursive >/dev/null 2>&1 || set_perm_recursive() { :; }

ui_print "********************************************"
ui_print "- Cirno Recycle+ v1.4.0 (作者: flyingr)"
ui_print "- 基于 Cirno (GPL-3.0) 二次修改"
ui_print "********************************************"

# ---------------------------------------------------------------------------
# 0. 基本环境检查
# ---------------------------------------------------------------------------
if [ -z "$API" ]; then
    API=$(getprop ro.build.version.sdk 2>/dev/null)
fi

case "$API" in
'' | *[!0-9]*)
    ui_print "! 无法读取 Android API 版本, 跳过版本检查"
    ;;
*)
    if [ "$API" -lt 31 ]; then
        abort "! 需要 Android 12 (API 31) 及以上, 当前 API=$API"
    fi
    ui_print "- Android API: $API"
    ;;
esac

ui_print "- 设备架构: ${ARCH:-unknown}"

case "$ARCH" in
arm64) ;;
*)
    ui_print "! 当前架构不是 arm64, 本模块主要在 arm64 设备上测试"
    ui_print "! 功能能否生效取决于内核是否支持 cgroup v2 / PSI"
    ;;
esac

if [ ! -f "$MODPATH/module.prop" ]; then
    abort "! 模块文件不完整 (module.prop 缺失), 请重新下载 zip"
fi

# ---------------------------------------------------------------------------
# 1. 安装配套 APK
#
# Magisk / KernelSU 在「已开机」状态下刷模块时, pm 是可用的;
# 在 recovery 里刷入时 BOOTMODE=false, 此时安装不了 APK, 交给 service.sh 开机后重试.
# ---------------------------------------------------------------------------
install_apk() {
    APK="$MODPATH/apk/CirnoRecyclePlus.apk"

    if [ ! -f "$APK" ]; then
        ui_print "! 模块包里没有找到 apk/CirnoRecyclePlus.apk"
        return 1
    fi

    if pm list packages 2>/dev/null | grep -q "package:$PKG"; then
        ui_print "- 已安装过 Cirno Recycle+, 覆盖安装/升级"
    fi

    ui_print "- 正在安装配套 APK (${PKG}) ..."

    # 依次尝试几种 pm 参数组合, 覆盖不同 Android 版本 / root 方案.
    if pm install -r -d -g --user 0 "$APK" >/dev/null 2>&1 ||
        pm install -r -d -g "$APK" >/dev/null 2>&1 ||
        pm install -r "$APK" >/dev/null 2>&1; then
        sleep 1
        if pm list packages 2>/dev/null | grep -q "package:$PKG"; then
            ui_print "- APK 安装成功"
            rm -rf "$MODPATH/apk"
            return 0
        fi
    fi

    ui_print "! APK 安装失败 (可能此时 PackageManager 尚未就绪)"
    ui_print "! 已记录, 开机后 service.sh 会自动重试安装"
    touch "$MODPATH/.apk_pending"
    return 1
}

if [ "$BOOTMODE" = "true" ]; then
    install_apk
else
    ui_print "- 当前不是开机状态 (recovery 刷入), APK 将在开机后自动安装"
    touch "$MODPATH/.apk_pending"
fi

# ---------------------------------------------------------------------------
# 2. 创建配置目录
#
# system_server 里的 Cirno Recycle+ 会读写 /data/misc/CirnoRecycle;
# 这里提前建好并交给 system 用户, 避免首次启动时因权限问题读不到配置.
# ---------------------------------------------------------------------------
CONFIG_DIR=/data/misc/CirnoRecycle
if mkdir -p "$CONFIG_DIR/log" 2>/dev/null; then
    chown system:system "$CONFIG_DIR" "$CONFIG_DIR/log" 2>/dev/null
    chmod 0770 "$CONFIG_DIR" "$CONFIG_DIR/log" 2>/dev/null
    chcon u:object_r:system_data_file:s0 "$CONFIG_DIR" 2>/dev/null
    ui_print "- 配置目录: $CONFIG_DIR"
else
    ui_print "! 配置目录创建失败, 首次启动后会由 system_server 自动创建"
fi

# ---------------------------------------------------------------------------
# 2.5 小米 / HyperOS millet 兼容
#
# 小米的 millet_core 内核模块 + Greeze 服务会和本模块同时冻结进程，导致
# 反复解冻 / 冻结、功耗升高。检测到运行中的 millet 时自动禁用系统 millet：
#   - 备份并关闭 persist.sys.gz.enable / millet.handshake / powmillet.enable；
#   - 在模块目录放一个空的 millet_core.ko，下次开机由 Magisk 覆盖原模块；
#   - 写 millet_disabled 标记，开机脚本会继续卸载已加载的内核模块。
# 不会修改 system 分区；卸载模块或在 App 里「恢复 millet」会还原.
# ---------------------------------------------------------------------------
ui_print "- 检查小米 / HyperOS millet (Greeze) ..."
if [ -f "$MODPATH/millet_ctl.sh" ]; then
    MILLET_OUT="$(sh "$MODPATH/millet_ctl.sh" auto 2>&1)"
    echo "$MILLET_OUT" | while IFS= read -r line; do
        case "$line" in
        -*) ui_print "  $line" ;;
        esac
    done
    case "$MILLET_OUT" in
    *"DISABLED=1"*)
        ui_print "- 已启用 millet 兼容模式, 重启后完全生效"
        ui_print "- 如需保留系统 millet: App -> 设置 -> 小米兼容 -> 恢复 millet"
        ;;
    *)
        ui_print "- 未启用 millet 兼容模式"
        ;;
    esac
else
    ui_print "! 模块包里缺少 millet_ctl.sh, 跳过 millet 兼容处理"
fi

# ---------------------------------------------------------------------------
# 3. 装一个「模块被删除后自动卸载 App」的清理脚本
#
# 模块卸载脚本执行时机很早, 那时 pm 往往还不能用; 放到 service.d 里等开机完成再处理.
# ---------------------------------------------------------------------------
SERVICE_D=/data/adb/service.d
if mkdir -p "$SERVICE_D" 2>/dev/null; then
    cat >"$SERVICE_D/cirno_recycle_plus_cleanup.sh" <<'EOFCLEAN'
#!/system/bin/sh
# 由 Cirno Recycle+ 安装时写入: 模块目录不存在时卸载配套 App 并自删.
MOD=/data/adb/modules/cirno_recycle_plus
[ -d "$MOD" ] && exit 0
i=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 30 ]; do
    i=$((i + 1))
    sleep 4
done
pm uninstall com.flyingr.cirnorecycle >/dev/null 2>&1
rm -f "$0"
exit 0
EOFCLEAN
    chmod 0755 "$SERVICE_D/cirno_recycle_plus_cleanup.sh" 2>/dev/null
    chcon u:object_r:magisk_file:s0 "$SERVICE_D/cirno_recycle_plus_cleanup.sh" 2>/dev/null
fi

# 模块被停用 / 删除时，恢复 millet 属性；模块正常启用时，开机后再应用一次
# millet 兼容模式（post-fs-data 里已经试过一次）.
if mkdir -p "$SERVICE_D" 2>/dev/null; then
    cat >"$SERVICE_D/cirno_recycle_plus_millet_guard.sh" <<'EOFMILlet'
#!/system/bin/sh
# 由 Cirno Recycle+ 安装时写入: 模块停用 / 删除时恢复 millet.
MOD=/data/adb/modules/cirno_recycle_plus
BACKUP=/data/misc/CirnoRecycle/millet_props.backup
i=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 30 ]; do
    i=$((i + 1))
    sleep 4
done
if [ -d "$MOD" ] && [ ! -f "$MOD/disable" ]; then
    [ -f "$MOD/millet_disabled" ] && [ -f "$MOD/millet_ctl.sh" ] && sh "$MOD/millet_ctl.sh" boot >/dev/null 2>&1
    exit 0
fi
# 模块被停用：优先用模块脚本恢复（会顺带清掉 marker / 覆盖文件）.
if [ -f "$MOD/millet_ctl.sh" ]; then
    sh "$MOD/millet_ctl.sh" restore-quiet >/dev/null 2>&1
    exit 0
fi
# 模块已经删除：直接按备份还原属性.
[ -f "$BACKUP" ] || exit 0
# 备份必须包含所有已知属性, 否则只恢复了一部分, 不应删除备份.
for p in persist.sys.gz.enable persist.sys.millet.handshake persist.sys.powmillet.enable; do
    grep -q "^$p=" "$BACKUP" 2>/dev/null || exit 0
done
failed=0
while IFS='=' read -r name value; do
    case "$name" in
        ''|'#'*) continue ;;
    esac
    if [ -z "$value" ]; then
        resetprop --delete "$name" >/dev/null 2>&1 || setprop "$name" "" >/dev/null 2>&1
        [ -z "$(getprop "$name")" ] || failed=1
    else
        resetprop "$name" "$value" >/dev/null 2>&1 || setprop "$name" "$value" >/dev/null 2>&1
        [ "$(getprop "$name")" = "$value" ] || failed=1
    fi
done <"$BACKUP"
if [ "$failed" -ne 0 ]; then
    # 恢复失败: 保留备份和守卫脚本, 下次开机继续尝试.
    exit 0
fi
rm -f "$BACKUP"
rm -f "$0"
exit 0
EOFMILlet
    chmod 0755 "$SERVICE_D/cirno_recycle_plus_millet_guard.sh" 2>/dev/null
    chcon u:object_r:magisk_file:s0 "$SERVICE_D/cirno_recycle_plus_millet_guard.sh" 2>/dev/null
fi

# ---------------------------------------------------------------------------
# 4. 文件权限
# ---------------------------------------------------------------------------
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/customize.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/millet_ctl.sh" 0 0 0755

# ---------------------------------------------------------------------------
# 5. 提示
# ---------------------------------------------------------------------------
ui_print "********************************************"
if [ -d /data/adb/lspd ] || [ -d /data/adb/modules/zygisk_lsposed ] || [ -d /data/adb/modules/lsposed ]; then
    ui_print "- 检测到 LSPosed"
else
    ui_print "! 没有检测到 LSPosed!"
    ui_print "! 本模块是 LSPosed 模块, 必须先安装 LSPosed (Zygisk 版)"
    ui_print "! 否则内存回收和冻结功能都不会生效"
fi
ui_print "- 刷入完成, 重启后:"
ui_print "  1) 打开 LSPosed -> 模块 -> 启用 Cirno Recycle+"
ui_print "     (作用域已在 APK 里静态声明为「系统框架」, 无需手动勾选)"
ui_print "  2) 打开 Cirno Recycle+ App, 底栏「设置」里按需开启内存回收"
ui_print "  3) 小米设备可在「设置 -> 小米兼容」里查看/恢复 millet"
ui_print "********************************************"
ui_print "- 配置目录: /data/misc/CirnoRecycle"
ui_print "- 运行日志: /data/misc/CirnoRecycle/log/current.log"
ui_print "- millet 日志: /data/misc/CirnoRecycle/log/millet.log"
ui_print "- 作者: flyingr  ·  版本: 1.4.0  ·  GPL-3.0"
ui_print "********************************************"
