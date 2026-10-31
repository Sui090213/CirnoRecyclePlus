package nep.timeline.cirno.client

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import nep.timeline.cirno.ui.ThemeMode

/**
 * App 自己的界面偏好 (主题 / 更新检查 / 列表展示).
 *
 * <p>这些设置只影响 App 自己的界面, 不参与 system_server 的冻结逻辑, 所以存在
 * SharedPreferences 里: 不需要 root, 改完立刻生效, 也不会和配置文件的热重载互相干扰.
 *
 * <p>字段全部用 Compose 的 [mutableStateOf]: 设置页改了值之后, 首页 / 应用面板会立即重组,
 * 不需要手动刷新.
 */
object AppPrefs {
    private const val FILE_NAME = "cirno_ui_prefs"

    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_MONET = "monet"
    private const val KEY_THEME_SEED = "theme_seed"
    private const val KEY_AUTO_UPDATE = "auto_update"
    private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
    private const val KEY_BACKGROUND_FIRST = "sort_background_first"
    private const val KEY_SHOW_INACTIVE_PROCESSES = "show_inactive_processes"

    /** 默认主题色: 雪蓝 (和图标里的冰蓝色接近). */
    const val DEFAULT_SEED = 0xFF4A90D9.toInt()

    private var prefs: SharedPreferences? = null

    /** 主题模式: 跟随系统 / 浅色 / 深色. */
    var themeMode by mutableStateOf(ThemeMode.SYSTEM)
        private set

    /** 是否使用 Android 12+ 的莫奈取色 (从壁纸取色). */
    var monetEnabled by mutableStateOf(false)
        private set

    /** 自定义主题色 (ARGB). 莫奈取色开启时忽略. */
    var themeSeed by mutableStateOf(DEFAULT_SEED)
        private set

    /** 打开 App 时自动检查更新. */
    var autoCheckUpdate by mutableStateOf(true)
        private set

    /** 首页把「在后台运行的应用」排到最前面. */
    var sortBackgroundFirst by mutableStateOf(true)
        private set

    /** 应用面板的进程列表显示「未运行 / 未活动的已声明进程」. */
    var showInactiveProcesses by mutableStateOf(true)
        private set

    /** 初始化 (进程内只做一次). 在 Application / Activity 启动时调用. */
    @Synchronized
    fun init(context: Context) {
        if (prefs != null)
            return

        val p = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        prefs = p
        themeMode = ThemeMode.fromValue(p.getInt(KEY_THEME_MODE, ThemeMode.SYSTEM.value))
        monetEnabled = p.getBoolean(KEY_MONET, false)
        themeSeed = p.getInt(KEY_THEME_SEED, DEFAULT_SEED)
        autoCheckUpdate = p.getBoolean(KEY_AUTO_UPDATE, true)
        sortBackgroundFirst = p.getBoolean(KEY_BACKGROUND_FIRST, true)
        showInactiveProcesses = p.getBoolean(KEY_SHOW_INACTIVE_PROCESSES, true)
    }

    fun updateThemeMode(value: ThemeMode) {
        if (themeMode == value)
            return
        themeMode = value
        edit { it.putInt(KEY_THEME_MODE, value.value) }
    }

    fun updateMonetEnabled(enabled: Boolean) {
        if (monetEnabled == enabled)
            return
        monetEnabled = enabled
        edit { it.putBoolean(KEY_MONET, enabled) }
    }

    fun updateThemeSeed(argb: Int) {
        if (themeSeed == argb)
            return
        themeSeed = argb
        edit { it.putInt(KEY_THEME_SEED, argb) }
    }

    fun updateAutoCheckUpdate(enabled: Boolean) {
        if (autoCheckUpdate == enabled)
            return
        autoCheckUpdate = enabled
        edit { it.putBoolean(KEY_AUTO_UPDATE, enabled) }
    }

    fun updateSortBackgroundFirst(enabled: Boolean) {
        if (sortBackgroundFirst == enabled)
            return
        sortBackgroundFirst = enabled
        edit { it.putBoolean(KEY_BACKGROUND_FIRST, enabled) }
    }

    fun updateShowInactiveProcesses(enabled: Boolean) {
        if (showInactiveProcesses == enabled)
            return
        showInactiveProcesses = enabled
        edit { it.putBoolean(KEY_SHOW_INACTIVE_PROCESSES, enabled) }
    }

    /** 上次检查更新的时间 (epoch 毫秒), 0 表示还没检查过. */
    fun lastUpdateCheckAt(): Long = prefs?.getLong(KEY_LAST_UPDATE_CHECK, 0L) ?: 0L

    /** 记录一次检查更新 (不管成功还是失败, 都算检查过, 避免失败时每次开 App 都重试). */
    fun markUpdateChecked(at: Long = System.currentTimeMillis()) {
        edit { it.putLong(KEY_LAST_UPDATE_CHECK, at) }
    }

    private fun edit(block: (SharedPreferences.Editor) -> Unit) {
        val p = prefs ?: return
        val editor = p.edit()
        block(editor)
        editor.apply()
    }
}
