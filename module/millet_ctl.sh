#!/system/bin/sh
# =============================================================================
# Cirno Recycle+ — 小米 millet（Greeze）兼容控制
#
# 小米 / Redmi / POCO 的 HyperOS 自带 millet_core 内核模块 + Greeze 系统服务。
# 它和本模块同时冻结后台进程时，会出现反复冻结 / 解冻，功耗升高。
#
# 这个脚本做三件事：
#   1. 检测：机型 + millet_core.ko / 已加载内核模块 / Greeze 服务 / millet 属性；
#   2. 禁用：备份原始属性 -> 关闭 Greeze 相关 persist 属性 -> 标记 + 模块目录
#      里放一个空的 millet_core.ko 覆盖文件（Magisk 下次开机 magic mount）；
#   3. 恢复：还原属性、删除覆盖文件和标记。
#
# 所有改动都是可逆的：不写 system 分区，不删系统文件；卸载模块或点「恢复」
# 会还原安装时保存的属性。
# =============================================================================

MODDIR=${0%/*}
[ "$MODDIR" = "." ] && MODDIR="$(pwd)"
MODULE_ID=cirno_recycle_plus
CONFIG_DIR=/data/misc/CirnoRecycle
LOG_DIR="$CONFIG_DIR/log"
LOG="$LOG_DIR/millet.log"
BACKUP="$CONFIG_DIR/millet_props.backup"
MARKER="$MODDIR/millet_disabled"
OVERLAY_LIST="$MODDIR/millet_overlay.list"

KNOWN_PROPS="persist.sys.gz.enable persist.sys.millet.handshake persist.sys.powmillet.enable"
OPTIONAL_PROPS="persist.sys.millet.enable"

mkdir -p "$LOG_DIR" 2>/dev/null

log() {
    echo "$(date '+%m-%d %H:%M:%S' 2>/dev/null) $*" >>"$LOG" 2>/dev/null
}

get_prop() {
    getprop "$1" 2>/dev/null
}

set_prop() {
    name="$1"
    value="$2"
    if command -v resetprop >/dev/null 2>&1; then
        resetprop "$name" "$value" >/dev/null 2>&1 && return 0
    fi
    setprop "$name" "$value" >/dev/null 2>&1
}

del_prop() {
    name="$1"
    if command -v resetprop >/dev/null 2>&1; then
        resetprop --delete "$name" >/dev/null 2>&1 && return 0
    fi
    setprop "$name" "" >/dev/null 2>&1
}

is_xiaomi() {
    combined="$(get_prop ro.product.brand) $(get_prop ro.product.manufacturer) $(get_prop ro.miui.ui.version.name) $(get_prop ro.mi.os.version.name)"
    combined="$(echo "$combined" | tr 'A-Z' 'a-z')"
    case "$combined" in
        *xiaomi* | *redmi* | *poco* | *hyperos* | *miui*) return 0 ;;
    esac
    # recovery 里 getprop 可能读不到系统属性，再从 build.prop 兜底.
    for f in /system/build.prop /system_ext/build.prop /vendor/build.prop /odm/build.prop; do
        [ -f "$f" ] || continue
        grep -Eqi 'xiaomi|redmi|poco|hyperos' "$f" 2>/dev/null && return 0
    done
    return 1
}

has_millet_files() {
    for f in \
        /system_ext/lib64/modules/millet_core.ko \
        /system_ext/lib/modules/millet_core.ko \
        /system/lib64/modules/millet_core.ko \
        /system/lib/modules/millet_core.ko \
        /vendor/lib64/modules/millet_core.ko \
        /vendor/lib/modules/millet_core.ko \
        /vendor_dlkm/lib64/modules/millet_core.ko \
        /vendor_dlkm/lib/modules/millet_core.ko \
        /odm/lib64/modules/millet_core.ko \
        /odm/lib/modules/millet_core.ko \
        /system_ext/etc/init/millet_core.rc \
        /system_ext/etc/init/init.millet_core.rc \
        /system/etc/init/millet_core.rc \
        /system/etc/init/init.millet_core.rc \
        /vendor/etc/init/millet_core.rc; do
        [ -e "$f" ] && return 0
    done
    return 1
}

has_millet_evidence() {
    is_xiaomi || return 1
    has_millet_files && return 0
    [ -d /sys/module/millet_core ] && return 0
    [ -d /sys/module/millet ] && return 0
    if command -v lsmod >/dev/null 2>&1; then
        lsmod 2>/dev/null | grep -qi 'millet' && return 0
    fi
    getprop 2>/dev/null | grep -qi 'millet' && return 0
    service list 2>/dev/null | grep -Eqi 'greez|millet' && return 0
    ps -A 2>/dev/null | grep -Eqi '[m]illet|[g]reeze' && return 0
    return 1
}

kernel_loaded() {
    [ -d /sys/module/millet_core ] || [ -d /sys/module/millet ] && return 0
    if command -v lsmod >/dev/null 2>&1; then
        lsmod 2>/dev/null | grep -Eqi '^millet'
        return $?
    fi
    return 1
}

other_millet_module() {
    for d in /data/adb/modules/*; do
        [ -d "$d" ] || continue
        [ "$d" = "$MODDIR" ] && continue
        [ "${d##*/}" = "$MODULE_ID" ] && continue
        [ -f "$d/module.prop" ] || continue
        if grep -Eqi 'millet|greeze' "$d/module.prop" 2>/dev/null; then
            echo "$d"
            return 0
        fi
    done
    return 1
}

backup_props() {
    [ -s "$BACKUP" ] && return 0
    mkdir -p "$CONFIG_DIR" 2>/dev/null
    : >"$BACKUP" 2>/dev/null || return 1
    for p in $KNOWN_PROPS; do
        printf '%s=%s\n' "$p" "$(get_prop "$p")" >>"$BACKUP"
    done
    for p in $OPTIONAL_PROPS; do
        v="$(get_prop "$p")"
        [ -n "$v" ] && printf '%s=%s\n' "$p" "$v" >>"$BACKUP"
    done
    log "已备份 millet 属性到 $BACKUP"
    return 0
}

restore_props() {
    if [ ! -s "$BACKUP" ]; then
        log "没有属性备份，跳过属性还原"
        return 1
    fi
    while IFS='=' read -r name value; do
        [ -n "$name" ] || continue
        if [ -z "$value" ]; then
            del_prop "$name"
        else
            set_prop "$name" "$value"
        fi
    done <"$BACKUP"
    rm -f "$BACKUP"
    log "已还原 millet 属性"
    return 0
}

apply_disabled_props() {
    for p in $KNOWN_PROPS; do
        set_prop "$p" false
    done
    # 某些 HyperOS 版本用这个开关；只有系统里存在时才改，避免凭空造属性.
    if [ -n "$(get_prop persist.sys.millet.enable)" ]; then
        set_prop persist.sys.millet.enable false
    fi
}

stop_millet_services() {
    for svc in millet millet_core greezer greeze; do
        [ -n "$(get_prop "init.svc.$svc")" ] && stop "$svc" >/dev/null 2>&1
    done
}

unload_kernel_module() {
    for m in millet_core millet; do
        [ -d "/sys/module/$m" ] || continue
        if rmmod "$m" >/dev/null 2>&1; then
            log "已卸载内核模块 $m"
        else
            log "内核模块 $m 正在使用，暂时无法卸载 (重启时再试)"
        fi
    done
}

create_overlay() {
    for base in system_ext system vendor vendor_dlkm odm; do
        for rel in lib64/modules/millet_core.ko lib/modules/millet_core.ko; do
            target="/$base/$rel"
            [ -e "$target" ] || continue
            item="$base/$rel"
            grep -qxF "$item" "$OVERLAY_LIST" 2>/dev/null || echo "$item" >>"$OVERLAY_LIST"
            dest="$MODDIR/$item"
            mkdir -p "$(dirname "$dest")" 2>/dev/null
            : >"$dest" 2>/dev/null
        done
    done
}

remove_overlay() {
    if [ -f "$OVERLAY_LIST" ]; then
        while IFS= read -r item; do
            [ -n "$item" ] && rm -f "$MODDIR/$item"
        done <"$OVERLAY_LIST"
        rm -f "$OVERLAY_LIST"
    fi

    # 只删文件还不够：空的 system_ext/.../modules 目录如果留在模块里，
    # Magisk 仍可能把空目录挂到系统目录上，恢复后系统 millet 还是被藏起来。
    # rmdir 只会删除空目录，不会碰模块里其他文件.
    for base in system_ext system vendor vendor_dlkm odm; do
        [ -d "$MODDIR/$base" ] || continue
        for rel in lib64/modules lib/modules; do
            [ -d "$MODDIR/$base/$rel" ] && rmdir "$MODDIR/$base/$rel" 2>/dev/null
        done
        [ -d "$MODDIR/$base/lib64" ] && rmdir "$MODDIR/$base/lib64" 2>/dev/null
        [ -d "$MODDIR/$base/lib" ] && rmdir "$MODDIR/$base/lib" 2>/dev/null
        rmdir "$MODDIR/$base" 2>/dev/null
    done
}

print_status() {
    xiaomi=0
    detected=0
    other=0
    disabled=0
    loaded=0
    backup=0

    is_xiaomi && xiaomi=1
    has_millet_evidence && detected=1
    other_millet_module >/dev/null 2>&1 && other=1
    [ -f "$MARKER" ] && disabled=1
    kernel_loaded && loaded=1
    [ -s "$BACKUP" ] && backup=1

    echo "MODULE=1"
    echo "XIAOMI=$xiaomi"
    echo "MILLET_DETECTED=$detected"
    echo "MILLET_DISABLED=$disabled"
    echo "OTHER_MODULE=$other"
    echo "KERNEL_LOADED=$loaded"
    echo "BACKUP=$backup"

    if [ "$xiaomi" -eq 0 ]; then
        echo "- 当前不是小米 / Redmi / POCO / HyperOS 设备"
    elif [ "$detected" -eq 0 ]; then
        echo "- 未检测到系统 millet（Greeze）"
    elif [ "$other" -eq 1 ]; then
        echo "- 检测到其他 millet 相关模块，跳过自动处理"
    elif [ "$disabled" -eq 1 ]; then
        echo "- 系统 millet 已禁用（重启后完全生效）"
    else
        echo "- 检测到系统 millet（Greeze），尚未禁用"
    fi
    if [ "$loaded" -eq 1 ]; then
        echo "- millet_core 内核模块当前已加载"
    fi
}

disable_millet() {
    backup_props
    apply_disabled_props
    create_overlay
    touch "$MARKER" 2>/dev/null
    log "已启用 millet 兼容模式（属性 + 内核模块覆盖），重启后完全生效"
    echo "DISABLED=1"
    echo "- 已禁用系统 millet（重启后完全生效）"
    echo "- 已备份原始属性；可在 App「设置 → 小米兼容」恢复"
}

enable_millet() {
    restore_props
    remove_overlay
    rm -f "$MARKER"
    log "已恢复 millet（属性 + 覆盖文件），重启后完全生效"
    echo "DISABLED=0"
    echo "- 已恢复系统 millet（重启后完全生效）"
}

boot_apply() {
    [ -f "$MARKER" ] || return 0
    # recovery 里刷入时 getprop 可能读不到真实属性，备份会是全空；
    # 开机时如果发现备份没有有效值，重新按当前属性备份，避免恢复时把原值删掉.
    if [ -s "$BACKUP" ]; then
        backup_has_value=0
        while IFS='=' read -r _name _value; do
            [ -n "$_value" ] && backup_has_value=1
        done <"$BACKUP"
        if [ "$backup_has_value" -eq 0 ]; then
            rm -f "$BACKUP"
        fi
    fi
    backup_props
    apply_disabled_props
    stop_millet_services
    unload_kernel_module
    create_overlay
    log "开机应用 millet 兼容模式完成"
}

auto_apply() {
    xiaomi=0
    detected=0
    other=0
    is_xiaomi && xiaomi=1
    has_millet_evidence && detected=1
    other_dir="$(other_millet_module 2>/dev/null)"
    [ -n "$other_dir" ] && other=1

    echo "XIAOMI=$xiaomi"
    echo "MILLET_DETECTED=$detected"
    echo "OTHER_MODULE=$other"

    if [ "$xiaomi" -eq 0 ]; then
        echo "- 非小米设备，跳过 millet 检测"
        return 0
    fi
    if [ "$other" -eq 1 ]; then
        echo "- 已安装其他 millet 相关模块：$other_dir"
        echo "- 跳过自动禁用，避免和它重复"
        return 0
    fi
    if [ "$detected" -eq 0 ]; then
        echo "- 未检测到系统 millet（Greeze），跳过"
        return 0
    fi

    echo "- 检测到小米 millet（Greeze）冻结服务"
    echo "- 它与本模块会互相抢后台进程，开始自动禁用"
    disable_millet
}

case "$1" in
    status)
        print_status
        ;;
    detect)
        is_xiaomi && has_millet_evidence
        exit $?
        ;;
    disable)
        disable_millet
        ;;
    enable | restore | restore-quiet)
        enable_millet
        ;;
    auto)
        auto_apply
        ;;
    boot)
        boot_apply
        ;;
    *)
        echo "usage: $0 {status|detect|disable|enable|auto|boot}"
        exit 1
        ;;
esac

exit 0
