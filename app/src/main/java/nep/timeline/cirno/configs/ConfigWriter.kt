package nep.timeline.cirno.configs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * 进程级「单线程配置写入队列」.
 *
 * <p>为什么不用 `rememberCoroutineScope()`:
 * [nep.timeline.cirno.screen.MemoryScreen] 里的写入如果挂在 composition scope 上,
 * 用户点完开关立刻返回时 composition 被销毁, 协程会被取消, 这次修改就静默丢失了.
 *
 * <p>为什么必须是单线程:
 * 每次写入都是「把内存里的完整配置快照序列化成 JSON 覆盖文件」. 如果两次写入并发,
 * 旧快照可能后落盘, 把新配置覆盖掉. 单线程队列保证按提交顺序落盘, 最后一次就是最新状态.
 */
object ConfigWriter {
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CirnoConfigWriter").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** 把一次写入排进队列; 失败只影响这一次, 不会拖垮后续写入. */
    fun submit(block: () -> Unit) {
        scope.launch {
            runCatching(block)
        }
    }
}
