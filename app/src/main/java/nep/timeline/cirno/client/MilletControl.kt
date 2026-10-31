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
        val detected: Boolean = false,
        val disabled: Boolean = false,
        val otherModule: Boolean = false,
        val kernelLoaded: Boolean = false,
        val backup: Boolean = false,
        val raw: String = "",
    )

    data class ActionResult(
        val ok: Boolean,
        val output: String,
        val disabled: Boolean?,
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
            detected = values["MILLET_DETECTED"] == "1",
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
        ActionResult(
            ok = result.code == 0 && !output.contains("MODULE=0"),
            output = if (output.isBlank()) "脚本没有输出" else output,
            disabled = disabled,
        )
    }
}
