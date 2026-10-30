package nep.timeline.cirno.client

import com.topjohnwu.superuser.io.SuFile
import nep.timeline.cirno.GlobalVars
import nep.timeline.cirno.runtime.AppRuntimeState
import nep.timeline.cirno.runtime.RuntimeAction
import nep.timeline.cirno.runtime.RuntimeActions
import nep.timeline.cirno.runtime.RuntimeFiles
import nep.timeline.cirno.runtime.RuntimeState
import nep.timeline.cirno.utils.RWUtils
import java.util.ArrayList

/**
 * App 侧的运行时状态客户端.
 *
 * <p>负责和 system_server 里的模块交换两个文件:
 * - 读 `RuntimeState.json`: 冻结状态 / 进程列表, 用于界面显示「已冻结」;
 * - 写 `RuntimeActions.json`: 手动冻结 / 解冻进程, 由 system_server 轮询执行.
 *
 * <p>所有方法都要求 root, 必须在 IO 线程调用 (内部用 libsu 的 SuFile, 每次都会走 root shell).
 */
object RuntimeClient {
    /** 状态文件超过这么久没更新就认为模块没在跑 (比如 LSPosed 里没启用 / 模块被停用). */
    private const val MAX_STATE_AGE_MS = 90_000L

    /** 操作文件里最多保留多少个未执行操作, 防止 system_server 长时间没跑时无限增长. */
    private const val MAX_PENDING_ACTIONS = 16

    /** 本模块最后一次读取失败的原因, 界面可以拿来做提示. */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * 读取当前运行时状态.
     *
     * @return 状态快照; 文件不存在 / 不是本次开机写的 / 已经过期 / 解析失败时返回 null,
     * 调用方应把这种情况当成「未知」, 不要显示成「未运行」.
     */
    fun loadState(): RuntimeState? {
        return try {
            val file = SuFile(GlobalVars.CONFIG_DIR, RuntimeFiles.STATE_FILE_NAME)
            if (!file.exists()) {
                lastError = null
                return null
            }

            val json = RWUtils.readConfig(file)
            if (json.isNullOrBlank()) {
                lastError = null
                return null
            }

            val state = try {
                RuntimeFiles.gson().fromJson(json, RuntimeState::class.java)
            } catch (t: Throwable) {
                null
            }

            if (state == null) {
                lastError = "运行状态文件解析失败"
                return null
            }

            // 上一次开机残留的状态, 或者模块已经停了很久: 都当作未知.
            if (Math.abs(state.bootTimeMs - RuntimeFiles.bootTimeMs()) > RuntimeFiles.BOOT_TIME_TOLERANCE_MS) {
                lastError = null
                return null
            }
            if (state.updatedAtMs <= 0L || System.currentTimeMillis() - state.updatedAtMs > MAX_STATE_AGE_MS) {
                lastError = "运行状态已过期 (模块可能没有在运行)"
                return null
            }

            lastError = null
            state
        } catch (t: Throwable) {
            lastError = t.message
            null
        }
    }

    /** 在状态快照里找某个应用的运行时状态 (没在运行的应用返回 null). */
    fun findApp(state: RuntimeState?, packageName: String, userId: Int): AppRuntimeState? {
        val apps = state?.apps ?: return null
        return apps.firstOrNull { it.packageName == packageName && it.userId == userId }
    }

    /**
     * 下发手动冻结 / 解冻操作.
     *
     * <p>为什么要合并「文件里还没执行的操作」: system_server 每 2 秒才轮询一次,
     * 用户 2 秒内连点两个进程时, 第二次写文件不能把第一次的批次覆盖掉. 这里读出现有批次,
     * 和新操作合并后写回去 (已经执行过的批次再执行一次是幂等的: 冻结已冻结的 / 解冻已解冻的
     * 都直接返回), 并保证 seq 单调递增.
     *
     * <p>用 [Synchronized] 串行化并发发送, 避免两个协程基于同一个旧 seq 各写一份、互相覆盖.
     *
     * @param executedSeq App 已知的「system_server 最后执行序号」(来自 RuntimeState.lastActionSeq).
     * 文件里的旧批次如果已经执行过 (seq <= executedSeq) 就不再合并, 避免重复执行 + 结果文案变长.
     *
     * @return 本次写入的操作序号 (system_server 执行后会把 lastActionSeq 写成它);
     * 失败返回 null
     */
    @Synchronized
    fun sendActions(actions: List<RuntimeAction>, executedSeq: Long = 0L): Long? {
        if (actions.isEmpty())
            return null

        return try {
            val file = SuFile(GlobalVars.CONFIG_DIR, RuntimeFiles.ACTIONS_FILE_NAME)

            var previousSeq = 0L
            val pending = ArrayList<RuntimeAction>()
            if (file.exists()) {
                val existing = try {
                    RWUtils.readConfig(file)
                } catch (t: Throwable) {
                    null
                }
                if (!existing.isNullOrBlank()) {
                    try {
                        val old = RuntimeFiles.gson().fromJson(existing, RuntimeActions::class.java)
                        if (old != null) {
                            previousSeq = old.seq
                            // 上一次开机留下的文件 / 已经执行过的批次都不需要合并.
                            val sameBoot = Math.abs(old.bootTimeMs - RuntimeFiles.bootTimeMs()) <= RuntimeFiles.BOOT_TIME_TOLERANCE_MS
                            if (sameBoot && old.seq > executedSeq) {
                                old.actions?.let { pending.addAll(it) }
                            }
                        }
                    } catch (t: Throwable) {
                        // 旧文件坏了也无所谓, 直接用当前时间戳.
                    }
                }
            }

            // 序号 = max(当前时间戳, 旧序号 + 1), 保证单调递增:
            // system_server 只执行序号更大的那一批, 序号回退会导致操作被忽略.
            val now = System.currentTimeMillis()
            var seq = now
            if (previousSeq in 1 until Long.MAX_VALUE && previousSeq >= seq)
                seq = previousSeq + 1
            if (seq <= 0L)
                seq = now

            val merged = ArrayList<RuntimeAction>()
            // 只保留最近的一小批未执行操作 + 本次新操作.
            val keep = (MAX_PENDING_ACTIONS - actions.size).coerceAtLeast(0)
            if (pending.size > keep)
                merged.addAll(pending.subList(pending.size - keep, pending.size))
            else
                merged.addAll(pending)
            merged.addAll(actions)

            val payload = RuntimeActions()
            payload.bootTimeMs = RuntimeFiles.bootTimeMs()
            payload.seq = seq
            payload.actions = merged

            // 临时文件 + mv 原子替换, 避免 system_server 读到半个 JSON.
            RWUtils.writeStringToFileSUAtomic(file, RuntimeFiles.gson().toJson(payload))
            seq
        } catch (t: Throwable) {
            lastError = t.message
            null
        }
    }
}
