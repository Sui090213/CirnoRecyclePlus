package nep.timeline.cirno.client

import android.os.Handler
import android.os.Looper
import nep.timeline.cirno.GlobalVars
import nep.timeline.cirno.configs.ConfigManager
import nep.timeline.cirno.configs.ConfigWriter
import nep.timeline.cirno.configs.checkers.AppConfigs
import nep.timeline.cirno.configs.settings.AppOptions
import nep.timeline.cirno.configs.settings.ApplicationSettings

/**
 * 单应用冻结选项的不可变快照 (Compose 只有不可变对象才能可靠触发重组).
 */
data class AppOptionsValue(
    val enabled: Boolean = true,
    val keepAudio: Boolean = true,
    val keepNetwork: Boolean = false,
    val keepAlarms: Boolean = false,
    val allowReclaim: Boolean = true,
    val processMode: Int = AppOptions.PROCESS_MODE_ALL,
    val selected: Set<String> = emptySet(),
)

/**
 * App 侧的「单应用冻结选项」读写.
 *
 * <p>选项存在 `ApplicationSettings.json` 的 `appOptions` 字段里, 和主进程的配置热重载共用
 * 同一套机制 (写文件 -> system_server 的 ConfigFileObserver 2 秒内重载).
 *
 * <p>线程约定:
 * - 读: [read] 内部会拿 [ConfigWriter.LOCK], 保证不会和写线程的 Gson 序列化并发;
 * - 写: 一律通过 [ConfigWriter] 的单线程队列, 并且「修改内存对象」也放在队列里做,
 *   不在主线程直接改 `GlobalVars.applicationSettings` 里的集合 (否则会和序列化线程抢集合).
 */
object AppOptionsStore {
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 从磁盘重新读一次配置 (走 root). 必须在 IO 线程调用. */
    fun load(): Boolean {
        return try {
            // 和写队列拿同一把锁: 队列里可能已经改了内存、正等着落盘, 这里读旧文件会把它覆盖掉.
            synchronized(ConfigWriter.LOCK) {
                ConfigManager.manager.readConfigSU()
            }
        } catch (t: Throwable) {
            false
        }
    }

    /** 读取应用选项快照; 没有配置时返回默认值 (不会写入). */
    fun read(packageName: String, userId: Int): AppOptionsValue {
        synchronized(ConfigWriter.LOCK) {
            val options = getOrCreate(packageName, userId)
            options.sanitize()
            return AppOptionsValue(
                enabled = options.enabled,
                keepAudio = options.keepAudio,
                keepNetwork = options.keepNetwork,
                keepAlarms = options.keepAlarms,
                allowReclaim = options.allowReclaim,
                processMode = options.processMode,
                selected = options.selected().toSet(),
            )
        }
    }

    /** 是否白名单 (白名单应用完全不冻结、也不参与内存回收). */
    fun isWhite(packageName: String, userId: Int): Boolean = AppConfigs.isWhiteApp(packageName, userId)

    /**
     * 修改应用选项并保存.
     *
     * <p>修改动作在写队列线程里执行, 不会和序列化并发; [onResult] 在主线程回调.
     */
    fun update(
        packageName: String,
        userId: Int,
        block: (AppOptions) -> Unit,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        ConfigWriter.submit {
            val ok = try {
                val options = getOrCreate(packageName, userId)
                block(options)
                options.sanitize()
                ConfigManager.manager.saveConfigSU()
            } catch (t: Throwable) {
                false
            }
            if (onResult != null)
                mainHandler.post { onResult(ok) }
        }
    }

    /**
     * 修改白名单并保存.
     *
     * @param onResult (是否有变化, 是否保存成功), 在主线程回调
     */
    fun setWhite(
        packageName: String,
        userId: Int,
        white: Boolean,
        onResult: ((changed: Boolean, saved: Boolean) -> Unit)? = null,
    ) {
        ConfigWriter.submit {
            var changed = false
            val ok = try {
                val settings = GlobalVars.applicationSettings
                    ?: ApplicationSettings().also { GlobalVars.applicationSettings = it }
                if (settings.whiteApps == null)
                    settings.whiteApps = LinkedHashSet()

                val key = AppConfigs.key(packageName, userId)
                changed = if (white) settings.whiteApps.add(key) else settings.whiteApps.remove(key)
                if (changed) ConfigManager.manager.saveConfigSU() else true
            } catch (t: Throwable) {
                false
            }
            if (onResult != null)
                mainHandler.post { onResult(changed, ok) }
        }
    }

    /**
     * 取应用选项对象 (只在写队列线程 / 持锁读时使用).
     *
     * <p>没有配置时会创建一份默认选项放进 map; 直接调用会修改共享配置对象, 必须持锁.
     */
    private fun getOrCreate(packageName: String, userId: Int): AppOptions {
        val settings = GlobalVars.applicationSettings
            ?: ApplicationSettings().also { GlobalVars.applicationSettings = it }

        if (settings.appOptions == null)
            settings.appOptions = LinkedHashMap()

        val key = AppConfigs.key(packageName, userId)
        return settings.appOptions.getOrPut(key) { AppOptions() }
    }
}
