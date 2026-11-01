#!/system/bin/sh
# =============================================================================
# Cirno Recycle+ — 小米 millet（Greeze）兼容控制
#
# 小米 / Redmi / POCO 的 HyperOS 自带 millet_core 内核模块 + Greeze 系统服务。
# 它和本模块同时冻结后台进程时，会出现反复冻结 / 解冻，功耗升高。
#
# v1.4 起把「检测到 millet」和「millet 正在运行、会冲突」分开：
#   - PRESENT: 设备上存在 millet 文件 / 属性 / 服务；
#   - ACTIVE : 内核模块已加载、Greeze 服务在跑、或相关 persist 属性为 true；
#   - CONFLICT: ACTIVE 且当前没有由本模块禁用（millet_disabled 标记）。
# 只有 CONFLICT 才自动禁用，避免仅仅因为系统里存在 .ko 文件就关掉一个没在工作的功能。
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
BACKUP_HEADER="# cirno_millet_backup_v1"

KNOWN_PROPS="persist.sys.gz.enable persist.sys.millet.handshake persist.sys.powmillet.enable"
OPTIONAL_PROPS="persist.sys.millet.enable"

mkdir -p "$LOG_DIR" 2>/dev/null

log() {
    echo "$(date '+%m-%d %H:%M:%S' 2>/dev/null) $*" >>"$LOG" 2>/dev/null
}

get_prop() {
    getprop "$1" 2>/dev/null
}

prop_is_true() {
    _pt_value="$(get_prop "$1")"
    case "$_pt_value" in
        true|TRUE|True|1|yes|YES|on|ON) return 0 ;;
    esac
    return 1
}

# 设置属性并校验; 优先 resetprop, 失败后回退 setprop.
set_prop() {
    _sp_name="$1"
    _sp_value="$2"
    if command -v resetprop >/dev/null 2>&1; then
        resetprop "$_sp_name" "$_sp_value" >/dev/null 2>&1
        [ "$(get_prop "$_sp_name")" = "$_sp_value" ] && return 0
    fi
    setprop "$_sp_name" "$_sp_value" >/dev/null 2>&1
    [ "$(get_prop "$_sp_name")" = "$_sp_value" ]
}

# 删除属性并校验 (原值不存在时使用).
del_prop() {
    _dp_name="$1"
    if command -v resetprop >/dev/null 2>&1; then
        resetprop --delete "$_dp_name" >/dev/null 2>&1
        [ -z "$(get_prop "$_dp_name")" ] && return 0
    fi
    setprop "$_dp_name" "" >/dev/null 2>&1
    [ -z "$(get_prop "$_dp_name")" ]
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

kernel_loaded() {
    [ -d /sys/module/millet_core ] || [ -d /sys/module/millet ] && return 0
    if command -v lsmod >/dev/null 2>&1; then
        lsmod 2>/dev/null | grep -Eqi '^millet'
        return $?
    fi
    return 1
}

# 设备上是否存在 millet 的「痕迹」. 只代表存在, 不代表正在运行.
millet_present() {
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

# millet 当前是否在运行 / 有实际冻结行为. 用于区分「只是存在」和「会冲突」.
millet_active() {
    kernel_loaded && return 0
    for _ma_svc in millet millet_core greezer greeze; do
        case "$(get_prop "init.svc.$_ma_svc")" in
            running|restarting) return 0 ;;
        esac
    done
    prop_is_true persist.sys.gz.enable && return 0
    prop_is_true persist.sys.millet.handshake && return 0
    prop_is_true persist.sys.powmillet.enable && return 0
    prop_is_true persist.sys.millet.enable && return 0
    ps -A 2>/dev/null | grep -Eqi '[m]illet|[g]reeze' && return 0
    return 1
}

is_disabled() {
    [ -f "$MARKER" ]
}

millet_conflict() {
    millet_active || return 1
    is_disabled && return 1
    return 0
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

backup_has_value() {
    [ -s "$BACKUP" ] || return 1
    while IFS='=' read -r _bh_name _bh_value; do
        case "$_bh_name" in
            ''|'#'*) continue ;;
        esac
        [ -n "$_bh_value" ] && return 0
    done <"$BACKUP"
    return 1
}

# 备份至少要为每个已知属性保留一行 (值可以为空), 否则恢复时会漏删 / 漏还原.
backup_valid() {
    [ -s "$BACKUP" ] || return 1
    for _bv_name in $KNOWN_PROPS; do
        grep -q "^$_bv_name=" "$BACKUP" 2>/dev/null || return 1
    done
    return 0
}

current_has_true_prop() {
    for _ctp in $KNOWN_PROPS $OPTIONAL_PROPS; do
        prop_is_true "$_ctp" && return 0
    done
    return 1
}

backup_props() {
    # 已经有完整备份: 直接复用. 旧版本备份没有 header 也兼容.
    if [ -s "$BACKUP" ] && backup_valid; then
        return 0
    fi
    # 旧备份损坏 / 只写了一半: 重新生成. 这里只在属性还没被本脚本改动时可靠;
    # 如果当前已经被改过, 会在日志里留下记录, 方便排查.
    if [ -s "$BACKUP" ]; then
        log "旧备份不完整，重新生成"
        rm -f "$BACKUP"
    fi
    mkdir -p "$CONFIG_DIR" 2>/dev/null || return 1

    _bp_tmp="${BACKUP}.tmp"
    {
        echo "$BACKUP_HEADER"
        for _bp_name in $KNOWN_PROPS; do
            printf '%s=%s\n' "$_bp_name" "$(get_prop "$_bp_name")"
        done
        for _bp_name in $OPTIONAL_PROPS; do
            _bp_value="$(get_prop "$_bp_name")"
            [ -n "$_bp_value" ] && printf '%s=%s\n' "$_bp_name" "$_bp_value"
        done
        # 可选属性不存在时上面的 `[ -n ... ] && printf` 会返回 1, 不能让它把
        # 整个重定向块判成失败; 这里显式保证块的最后一条命令成功.
        :
    } >"$_bp_tmp" 2>/dev/null || {
        rm -f "$_bp_tmp"
        return 1
    }

    # 校验备份文件确实写入且带 header, 避免把空文件当成功.
    [ -s "$_bp_tmp" ] || {
        rm -f "$_bp_tmp"
        return 1
    }
    grep -q "^$BACKUP_HEADER" "$_bp_tmp" 2>/dev/null || {
        rm -f "$_bp_tmp"
        return 1
    }
    mv -f "$_bp_tmp" "$BACKUP" 2>/dev/null || {
        rm -f "$_bp_tmp"
        return 1
    }
    log "已备份 millet 属性到 $BACKUP"
    return 0
}

# 恢复属性. 任何一条写入 / 校验失败都会返回非 0, 并保留备份.
restore_props() {
    if [ ! -s "$BACKUP" ]; then
        log "没有属性备份，跳过属性还原"
        return 1
    fi
    if ! backup_valid; then
        log "属性备份不完整，拒绝恢复; 保留备份 $BACKUP"
        return 1
    fi

    _rp_failed=0
    while IFS='=' read -r _rp_name _rp_value; do
        case "$_rp_name" in
            ''|'#'*) continue ;;
        esac
        if [ -z "$_rp_value" ]; then
            del_prop "$_rp_name" || _rp_failed=1
            [ -z "$(get_prop "$_rp_name")" ] || _rp_failed=1
        else
            set_prop "$_rp_name" "$_rp_value" || _rp_failed=1
            [ "$(get_prop "$_rp_name")" = "$_rp_value" ] || _rp_failed=1
        fi
        if [ "$_rp_failed" -ne 0 ]; then
            log "恢复属性失败: $_rp_name (期望=$_rp_value 实际=$(get_prop "$_rp_name"))"
            # 不 break: 尽量把其它属性也恢复, 但最终仍返回失败.
        fi
    done <"$BACKUP"

    if [ "$_rp_failed" -ne 0 ]; then
        log "属性恢复失败，保留备份 $BACKUP 以便重试"
        return 1
    fi

    # 属性已全部恢复; 备份删除失败不算致命, 保留文件即可.
    rm -f "$BACKUP" 2>/dev/null || log "备份删除失败 (不影响恢复): $BACKUP"
    log "已还原 millet 属性"
    return 0
}

disable_props() {
    _dp_failed=0
    for _dp_name in $KNOWN_PROPS; do
        if ! set_prop "$_dp_name" false; then
            _dp_failed=1
            log "写入属性失败: $_dp_name"
        fi
        [ "$(get_prop "$_dp_name")" = "false" ] || _dp_failed=1
    done
    # 某些 HyperOS 版本用这个开关；只有系统里存在时才改，避免凭空造属性.
    if [ -n "$(get_prop persist.sys.millet.enable)" ]; then
        if ! set_prop persist.sys.millet.enable false; then
            _dp_failed=1
            log "写入属性失败: persist.sys.millet.enable"
        fi
        [ "$(get_prop persist.sys.millet.enable)" = "false" ] || _dp_failed=1
    fi
    return $_dp_failed
}

stop_millet_services() {
    for _sms_svc in millet millet_core greezer greeze; do
        [ -n "$(get_prop "init.svc.$_sms_svc")" ] && stop "$_sms_svc" >/dev/null 2>&1
    done
}

unload_kernel_module() {
    for _ukm_name in millet_core millet; do
        [ -d "/sys/module/$_ukm_name" ] || continue
        if rmmod "$_ukm_name" >/dev/null 2>&1; then
            log "已卸载内核模块 $_ukm_name"
        else
            log "内核模块 $_ukm_name 正在使用，暂时无法卸载 (重启时再试)"
        fi
    done
}

# 创建零字节 .ko 覆盖文件. 返回非 0 表示有文件写入失败.
create_overlay() {
    _co_failed=0
    _co_count=0
    for _co_base in system_ext system vendor vendor_dlkm odm; do
        for _co_rel in lib64/modules/millet_core.ko lib/modules/millet_core.ko; do
            _co_target="/$_co_base/$_co_rel"
            [ -e "$_co_target" ] || continue
            _co_item="$_co_base/$_co_rel"
            _co_dest="$MODDIR/$_co_item"
            mkdir -p "$(dirname "$_co_dest")" 2>/dev/null || _co_failed=1
            : >"$_co_dest" 2>/dev/null || _co_failed=1
            [ -f "$_co_dest" ] || _co_failed=1
            if ! grep -qxF "$_co_item" "$OVERLAY_LIST" 2>/dev/null; then
                echo "$_co_item" >>"$OVERLAY_LIST" 2>/dev/null || _co_failed=1
            fi
            _co_count=$((_co_count + 1))
        done
    done
    [ "$_co_count" -gt 0 ] && log "已创建 $_co_count 个 millet_core.ko 覆盖文件"
    return $_co_failed
}

# 删除覆盖文件. 不依赖 millet_overlay.list: 即使列表丢失, 也按已知路径清理一遍.
remove_overlay() {
    _ro_failed=0
    if [ -f "$OVERLAY_LIST" ]; then
        while IFS= read -r _ro_item; do
            [ -n "$_ro_item" ] || continue
            if [ -e "$MODDIR/$_ro_item" ]; then
                rm -f "$MODDIR/$_ro_item" 2>/dev/null || _ro_failed=1
            fi
        done <"$OVERLAY_LIST"
        rm -f "$OVERLAY_LIST" 2>/dev/null || _ro_failed=1
    fi

    for _ro_base in system_ext system vendor vendor_dlkm odm; do
        for _ro_rel in lib64/modules/millet_core.ko lib/modules/millet_core.ko; do
            _ro_dest="$MODDIR/$_ro_base/$_ro_rel"
            if [ -e "$_ro_dest" ]; then
                rm -f "$_ro_dest" 2>/dev/null || _ro_failed=1
            fi
        done
    done

    # 只删空目录; 如果模块里还有别的东西, rmdir 会失败, 这是预期的.
    for _ro_base in system_ext system vendor vendor_dlkm odm; do
        for _ro_rel in lib64/modules lib/modules lib64 lib; do
            [ -d "$MODDIR/$_ro_base/$_ro_rel" ] && rmdir "$MODDIR/$_ro_base/$_ro_rel" 2>/dev/null
        done
        [ -d "$MODDIR/$_ro_base" ] && rmdir "$MODDIR/$_ro_base" 2>/dev/null
    done

    # 再校验一遍已知覆盖路径.
    for _ro_base in system_ext system vendor vendor_dlkm odm; do
        for _ro_rel in lib64/modules/millet_core.ko lib/modules/millet_core.ko; do
            [ -e "$MODDIR/$_ro_base/$_ro_rel" ] && _ro_failed=1
        done
    done
    return $_ro_failed
}

print_status() {
    _ps_xiaomi=0
    _ps_present=0
    _ps_active=0
    _ps_conflict=0
    _ps_disabled=0
    _ps_loaded=0
    _ps_backup=0
    _ps_other=0

    is_xiaomi && _ps_xiaomi=1
    millet_present && _ps_present=1
    millet_active && _ps_active=1
    other_millet_module >/dev/null 2>&1 && _ps_other=1
    is_disabled && _ps_disabled=1
    [ -f "$MARKER" ] && _ps_disabled=1
    kernel_loaded && _ps_loaded=1
    [ -s "$BACKUP" ] && _ps_backup=1
    [ "$_ps_active" -eq 1 ] && [ "$_ps_disabled" -eq 0 ] && _ps_conflict=1

    echo "MODULE=1"
    echo "XIAOMI=$_ps_xiaomi"
    echo "MILLET_PRESENT=$_ps_present"
    echo "MILLET_DETECTED=$_ps_present"
    echo "MILLET_ACTIVE=$_ps_active"
    echo "CONFLICT=$_ps_conflict"
    echo "MILLET_DISABLED=$_ps_disabled"
    echo "OTHER_MODULE=$_ps_other"
    echo "KERNEL_LOADED=$_ps_loaded"
    echo "BACKUP=$_ps_backup"

    if [ "$_ps_xiaomi" -eq 0 ]; then
        echo "- 当前不是小米 / Redmi / POCO / HyperOS 设备"
    elif [ "$_ps_present" -eq 0 ]; then
        echo "- 未检测到系统 millet（Greeze）"
    elif [ "$_ps_other" -eq 1 ]; then
        echo "- 检测到其他 millet 相关模块，自动处理会跳过"
    elif [ "$_ps_disabled" -eq 1 ]; then
        echo "- 系统 millet 已禁用（重启后完全生效）"
        [ "$_ps_loaded" -eq 1 ] && echo "- millet_core 内核模块仍加载，开机会继续尝试卸载"
    elif [ "$_ps_conflict" -eq 1 ]; then
        echo "- 检测到运行中的 millet（Greeze），会与本模块冲突"
    elif [ "$_ps_present" -eq 1 ]; then
        echo "- 检测到 millet 文件/属性，但当前没有活动服务；未判定为冲突"
    fi
    if [ "$_ps_loaded" -eq 1 ] && [ "$_ps_disabled" -eq 0 ]; then
        echo "- millet_core 内核模块当前已加载"
    fi
}

disable_millet() {
    if ! backup_props; then
        log "备份属性失败，已中止禁用"
        echo "ERROR=BACKUP_FAILED"
        echo "DISABLED=0"
        echo "- 备份原始属性失败，已中止（未修改系统）"
        return 1
    fi

    if ! disable_props; then
        log "属性写入/校验失败，尝试回滚"
        restore_props >/dev/null 2>&1
        echo "ERROR=PROP_WRITE_FAILED"
        echo "DISABLED=0"
        echo "- 属性写入/校验失败，已尝试回滚，未启用兼容模式"
        return 1
    fi

    create_overlay
    _dm_overlay_ok=$?

    if ! touch "$MARKER" 2>/dev/null; then
        log "写入 millet_disabled 标记失败，尝试回滚属性"
        restore_props >/dev/null 2>&1
        echo "ERROR=MARKER_FAILED"
        echo "DISABLED=0"
        echo "- 写入标记失败，已尝试回滚，未启用兼容模式"
        return 1
    fi

    log "已启用 millet 兼容模式（属性 + 内核模块覆盖），重启后完全生效"
    echo "DISABLED=1"
    echo "OVERLAY_OK=$((1 - _dm_overlay_ok))"
    echo "- 已禁用系统 millet（重启后完全生效）"
    echo "- 已备份原始属性；可在 App「设置 → 小米兼容」恢复"
    if [ "$_dm_overlay_ok" -ne 0 ]; then
        echo "ERROR=OVERLAY_FAILED"
        echo "- 属性已关闭，但覆盖文件写入不完整；开机会再试一次"
    fi
    return 0
}

enable_millet() {
    _em_restored=0
    if [ -s "$BACKUP" ]; then
        if ! restore_props; then
            echo "ERROR=PROP_RESTORE_FAILED"
            echo "DISABLED=1"
            echo "PROPS_RESTORED=0"
            echo "- 恢复属性失败，已保留备份和禁用标记；请检查 root / SELinux 后重试"
            return 1
        fi
        _em_restored=1
    else
        log "没有属性备份，只清理覆盖文件和标记"
    fi

    remove_overlay
    _em_overlay_ok=$?

    if ! rm -f "$MARKER" 2>/dev/null; then
        echo "ERROR=MARKER_REMOVE_FAILED"
        echo "DISABLED=1"
        echo "PROPS_RESTORED=$_em_restored"
        echo "- 删除禁用标记失败，请重试"
        return 1
    fi

    log "已恢复 millet（属性 + 覆盖文件），重启后完全生效"
    echo "DISABLED=0"
    echo "PROPS_RESTORED=$_em_restored"
    echo "OVERLAY_REMOVED=$((1 - _em_overlay_ok))"
    echo "- 已恢复系统 millet（重启后完全生效）"
    if [ "$_em_restored" -eq 0 ]; then
        echo "- 注意：没有找到属性备份，persist 属性未能还原"
    fi
    if [ "$_em_overlay_ok" -ne 0 ]; then
        echo "ERROR=OVERLAY_RESTORE_FAILED"
        echo "- 部分覆盖文件未删除；消重启后仍可能隐藏系统 millet，请重试"
        return 1
    fi
    return 0
}

boot_apply() {
    is_disabled || return 0

    # recovery 里刷入时 getprop 可能读不到真实属性, 备份会是全空.
    # 开机时如果备份没有有效值、但当前属性是 true, 说明之前备份不可信, 重新备份.
    if [ -s "$BACKUP" ] && ! backup_has_value; then
        if current_has_true_prop; then
            log "检测到旧备份全空且当前属性为 true，重新备份"
            rm -f "$BACKUP"
        fi
    fi

    if ! backup_props; then
        log "开机备份属性失败，保留标记，下次再试"
        return 1
    fi
    if ! disable_props; then
        log "开机写入属性失败，保留标记，下次再试"
        return 1
    fi

    stop_millet_services
    unload_kernel_module
    create_overlay || log "开机创建覆盖文件不完整，下次再试"
    log "开机应用 millet 兼容模式完成"
    return 0
}

auto_apply() {
    _aa_xiaomi=0
    _aa_present=0
    _aa_active=0
    _aa_conflict=0
    _aa_other=0
    _aa_other_dir=""

    is_xiaomi && _aa_xiaomi=1
    millet_present && _aa_present=1
    millet_active && _aa_active=1
    _aa_other_dir="$(other_millet_module 2>/dev/null)"
    [ -n "$_aa_other_dir" ] && _aa_other=1
    [ "$_aa_active" -eq 1 ] && ! is_disabled && _aa_conflict=1

    echo "XIAOMI=$_aa_xiaomi"
    echo "MILLET_PRESENT=$_aa_present"
    echo "MILLET_DETECTED=$_aa_present"
    echo "MILLET_ACTIVE=$_aa_active"
    echo "CONFLICT=$_aa_conflict"
    echo "OTHER_MODULE=$_aa_other"

    if [ "$_aa_xiaomi" -eq 0 ]; then
        echo "- 非小米设备，跳过 millet 检测"
        return 0
    fi
    if [ "$_aa_other" -eq 1 ]; then
        echo "- 已安装其他 millet 相关模块：$_aa_other_dir"
        echo "- 跳过自动禁用，避免和它重复"
        return 0
    fi
    if is_disabled; then
        echo "DISABLED=1"
        echo "- 已经处于 millet 兼容模式，跳过"
        return 0
    fi
    if [ "$_aa_present" -eq 0 ]; then
        echo "- 未检测到系统 millet（Greeze），跳过"
        return 0
    fi
    if [ "$_aa_active" -eq 0 ]; then
        echo "- 检测到 millet 文件/属性，但当前没有活动的 Greeze 服务"
        echo "- 未判定为冲突，跳过自动禁用"
        return 0
    fi

    echo "- 检测到运行中的 millet（Greeze），会与本模块抢后台进程"
    echo "- 开始自动禁用"
    disable_millet
}

case "$1" in
    status)
        print_status
        ;;
    detect)
        millet_present
        exit $?
        ;;
    disable)
        disable_millet
        exit $?
        ;;
    enable | restore | restore-quiet)
        enable_millet
        exit $?
        ;;
    auto)
        auto_apply
        exit $?
        ;;
    boot)
        boot_apply
        exit $?
        ;;
    *)
        echo "usage: $0 {status|detect|disable|enable|auto|boot}"
        exit 1
        ;;
esac

exit 0
