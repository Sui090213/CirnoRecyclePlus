package nep.timeline.cirno.client

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 小米 millet（Greeze）兼容控制.
 *
 * <p>真正的检测 / 属性读写 / 内核模块覆盖都在 Magisk 模块里的 `millet_ctl.sh`，
 * App 只负责用 root 调脚本并展示结果. 这样刷入时、开机时、App 里点按钮时
 * 用的是同一套逻辑，不会出现三份实现互相打架.
 */
object MilletControl {
    private const val MODULE_ID = "cirno_recycle_plus"
    private const val SCRIPT = "/data/adb/modules/$MODULE_ID/millet_ctl.sh"

    data class Status(
        val root: Boolean = false,
        val moduleInstalled: Boolean = false,
        val xiaomi: Boolean = false,
        /** 设备上存在 millet 文件 / 属性 / 服务（不代表正在运行）. */
        val present: Boolean = false,
        /** millet 当前正在运行（内核模块加载 / Greeze 服务 / 相关属性为 true）. */
        val active: Boolean = false,
        /** 正在运行且尚未被本模块禁用，会与本模块冲突. */
        val conflict: Boolean = false,
        val disabled: Boolean = false,
        val otherModule: Boolean = false,
        val kernelLoaded: Boolean = false,
        val backup: Boolean = false,
        val raw: String = "",
    ) {
        /** 兼容旧 UI / 旧字段. */
        val detected: Boolean get() = present
    }

    data class ActionResult(
        val ok: Boolean,
        val output: String,
        val disabled: Boolean?,
        /** 命令退出码 0，但脚本报告了部分失败（例如属性已改、覆盖文件没写全）. */
        val partial: Boolean = false,
    )

    suspend fun status(): Status = withContext(Dispatchers.IO) {
        val root = runCatching { Shell.getShell().isRoot }.getOrDefault(false)
        if (!root) {
            return@withContext Status(root = false)
        }
        val result = runCatching {
            Shell.cmd("[ -f $SCRIPT ] && sh $SCRIPT status 2>/dev/null || echo MODULE=0")
                .exec()
        }.getOrNull()
        if (result == null) {
            return@withContext Status(root = true)
        }
        val lines = result.out
        val values = lines.mapNotNull { line ->
            val index = line.indexOf('=')
            if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
        }.toMap()
        Status(
            root = true,
            moduleInstalled = values["MODULE"] == "1",
            xiaomi = values["XIAOMI"] == "1",
            present = values["MILLET_PRESENT"] == "1" || values["MILLET_DETECTED"] == "1",
            active = values["MILLET_ACTIVE"] == "1",
            conflict = values["CONFLICT"] == "1",
            disabled = values["MILLET_DISABLED"] == "1",
            otherModule = values["OTHER_MODULE"] == "1",
            kernelLoaded = values["KERNEL_LOADED"] == "1",
            backup = values["BACKUP"] == "1",
            raw = lines.joinToString("\n"),
        )
    }

    /** 只允许这两个动作，避免把任意字符串拼进 root shell. */
    suspend fun disable(): ActionResult = runAction("disable")

    suspend fun enable(): ActionResult = runAction("enable")

    private suspend fun runAction(action: String): ActionResult = withContext(Dispatchers.IO) {
        require(action == "disable" || action == "enable")
        val root = runCatching { Shell.getShell().isRoot }.getOrDefault(false)
        if (!root) {
            return@withContext ActionResult(false, "需要 root 权限", null)
        }
        val result = runCatching {
            Shell.cmd("[ -f $SCRIPT ] && sh $SCRIPT $action 2>&1 || echo MODULE=0")
                .exec()
        }.getOrNull()
            ?: return@withContext ActionResult(false, "执行 millet_ctl.sh 失败", null)
        val output = result.out.joinToString("\n").trim()
        val disabled = when {
            output.contains("DISABLED=1") -> true
            output.contains("DISABLED=0") -> false
            else -> null
        }
        val errorLine = output.lineSequence().firstOrNull { it.startsWith("ERROR=") }
        val failed = result.code != 0 || output.contains("MODULE=0")
        val missingPropsBackup = action == "enable" && output.contains("PROPS_RESTORED=0")
        return@withContext ActionResult(
            ok = !failed && errorLine == null && !missingPropsBackup,
            output = if (output.isBlank()) "脚本没有输出" else output,
            disabled = disabled,
            // 退出码 0 但有 ERROR / 没有属性备份: 属性可能没完全恢复, 但仍算部分成功.
            partial = !failed && (errorLine != null || missingPropsBackup),
        )
    }
}
