#!/system/bin/sh
# =============================================================================
# ColorOS 17 / ColorOS 12+ 系统更新禁用
#
# 思路: 不修改 system 分区, 只用 PackageManager 把 OTA 客户端设为 disabled-user;
#        卸载模块时再 enable 回来. 比改 build.prop / 删系统文件安全, 也容易回滚.
#
# 用法:
#   sh ota_block.sh apply    # 禁用
#   sh ota_block.sh restore  # 恢复
#   sh ota_block.sh status   # 查看状态
# =============================================================================

MODDIR=${0%/*}
STATE_DIR=/data/adb/cirno_coloros_ota
LOG_FILE="$STATE_DIR/ota_block.log"
APPLIED_FILE="$STATE_DIR/applied_packages"
PREV_SETTING_FILE="$STATE_DIR/previous_ota_setting"

# ColorOS 12+ 常见的 OTA 包名; 不存在的包会自动跳过.
PACKAGES="
com.oplus.ota
com.oplus.otaui
com.oplus.sau
com.oplus.romupdate
com.oplus.systemupdate
com.oplus.appupdate
com.oplus.otaupdate
com.coloros.ota
com.coloros.otaui
com.coloros.sau
com.coloros.romupdate
com.oppo.ota
com.oppo.otaui
com.oppo.sau
com.oppo.romupdate
"

mkdir -p "$STATE_DIR" 2>/dev/null

log() {
    echo "$(date '+%F %T') $*" >>"$LOG_FILE" 2>/dev/null
    echo "$*"
}

installed_packages() {
    pm list packages 2>/dev/null | sed 's/^package://'
}

save_previous_setting() {
    [ -f "$PREV_SETTING_FILE" ] && return 0
    if command -v settings >/dev/null 2>&1; then
        settings get global ota_disable_automatic_update >"$PREV_SETTING_FILE" 2>/dev/null || echo "null" >"$PREV_SETTING_FILE"
    else
        echo "null" >"$PREV_SETTING_FILE"
    fi
}

apply_block() {
    local installed pkg
    installed="$(installed_packages)"
    save_previous_setting
    [ -f "$APPLIED_FILE" ] || : >"$APPLIED_FILE"

    for pkg in $PACKAGES; do
        echo "$installed" | grep -qx "$pkg" || continue

        if pm disable-user --user 0 "$pkg" >/dev/null 2>&1; then
            grep -qx "$pkg" "$APPLIED_FILE" 2>/dev/null || echo "$pkg" >>"$APPLIED_FILE"
            log "已禁用 $pkg"
        elif pm suspend --user 0 "$pkg" >/dev/null 2>&1; then
            grep -qx "$pkg" "$APPLIED_FILE" 2>/dev/null || echo "$pkg" >>"$APPLIED_FILE"
            log "已挂起 $pkg (disable-user 不可用)"
        else
            log "跳过 $pkg (disable-user / suspend 都失败)"
        fi
    done

    # AOSP / ColorOS 都可能读取这个开关; 即使系统不认, 也不影响包禁用.
    if command -v settings >/dev/null 2>&1; then
        settings put global ota_disable_automatic_update 1 >/dev/null 2>&1
    fi

    log "完成: 已处理 $(wc -l <"$APPLIED_FILE" 2>/dev/null || echo 0) 个 OTA 包"
}

restore_updates() {
    local pkg prev
    if [ -f "$APPLIED_FILE" ]; then
        while IFS= read -r pkg; do
            [ -n "$pkg" ] || continue
            pm enable --user 0 "$pkg" >/dev/null 2>&1
            pm unsuspend --user 0 "$pkg" >/dev/null 2>&1
            log "已恢复 $pkg"
        done <"$APPLIED_FILE"
        rm -f "$APPLIED_FILE"
    fi

    if [ -f "$PREV_SETTING_FILE" ]; then
        prev="$(cat "$PREV_SETTING_FILE" 2>/dev/null)"
        if [ -z "$prev" ] || [ "$prev" = "null" ]; then
            settings delete global ota_disable_automatic_update >/dev/null 2>&1
        else
            settings put global ota_disable_automatic_update "$prev" >/dev/null 2>&1
        fi
        rm -f "$PREV_SETTING_FILE"
    fi

    log "已恢复系统更新包"
}

show_status() {
    local pkg
    echo "Cirno ColorOS 17 系统更新禁用"
    echo "状态目录: $STATE_DIR"
    if [ ! -f "$APPLIED_FILE" ]; then
        echo "当前没有由本模块禁用的包"
        return 0
    fi

    echo "由本模块处理的包:"
    while IFS= read -r pkg; do
        [ -n "$pkg" ] || continue
        if pm list packages -d 2>/dev/null | grep -qx "package:$pkg"; then
            echo "  [已禁用] $pkg"
        else
            echo "  [未禁用] $pkg"
        fi
    done <"$APPLIED_FILE"
}

case "$1" in
apply)
    apply_block
    ;;
restore)
    restore_updates
    ;;
status | "")
    show_status
    ;;
*)
    echo "用法: sh ota_block.sh {apply|restore|status}"
    exit 1
    ;;
esac

exit 0
