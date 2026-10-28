#!/system/bin/sh
# =============================================================================
# Cirno Recycle+ 模块动作 (Magisk / KernelSU 模块页的「操作」按钮)
#
# 打印当前状态并尝试打开设置界面.
# =============================================================================

MODDIR=${0%/*}
PKG=com.flyingr.cirnorecycle

echo "********************************************"
echo "- Cirno Recycle+ v1.0.0 (flyingr)"
echo "********************************************"

# 模块是否被禁用
if [ -f "$MODDIR/disable" ]; then
    echo "- 模块状态: 已禁用 (删除 disable 文件后重启生效)"
else
    echo "- 模块状态: 已启用"
fi

# APK 是否安装
if pm list packages 2>/dev/null | grep -q "package:$PKG"; then
    echo "- 配套 APK: 已安装 ($PKG)"
else
    echo "- 配套 APK: 未安装! 请安装 $MODDIR/apk/CirnoRecyclePlus.apk"
fi

# LSPosed
if [ -d /data/adb/lspd ] || [ -d /data/adb/modules/zygisk_lsposed ] || [ -d /data/adb/modules/lsposed ]; then
    echo "- LSPosed: 已安装"
else
    echo "- LSPosed: 未安装 (本模块必须依赖 LSPosed)"
fi

# 内存压力
if [ -r /proc/pressure/memory ]; then
    echo "- 内存 PSI:"
    cat /proc/pressure/memory | sed 's/^/    /'
else
    echo "- 内存 PSI: 内核未开启 PSI"
fi

# 冻结进程数
FROZEN=0
for f in /sys/fs/cgroup/apps/uid_*/pid_*/cgroup.freeze \
    /sys/fs/cgroup/uid_*/pid_*/cgroup.freeze \
    /sys/fs/cgroup/system/uid_*/pid_*/cgroup.freeze; do
    [ -f "$f" ] || continue
    [ "$(cat "$f" 2>/dev/null)" = "1" ] && FROZEN=$((FROZEN + 1))
done
echo "- 已冻结进程数: $FROZEN"

if [ -f /data/misc/CirnoRecycle/log/current.log ]; then
    echo "- 最近日志:"
    tail -n 8 /data/misc/CirnoRecycle/log/current.log | sed 's/^/    /'
else
    echo "- 最近日志: 还没有日志 (确认 LSPosed 里已启用模块并重启)"
fi

echo "********************************************"
echo "- 正在打开设置界面..."
am start -n "$PKG/.MemoryActivity" --user 0 >/dev/null 2>&1
echo "********************************************"
