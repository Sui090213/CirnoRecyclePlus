package nep.timeline.cirno.client

import android.content.pm.PackageManager

/**
 * 枚举一个应用「可能存在的全部进程」.
 *
 * <p>系统的进程列表 (RuntimeState.json) 只包含当前活着的进程; 但用户配置进程规则时,
 * 经常需要给一个「现在还没启动」的子进程 (例如微信的 :push) 预先设置规则.
 * 这里从 PackageManager 的四大组件声明里把 `android:process` 收集出来, 再加上主进程,
 * 这样即使进程没在跑也能勾选.
 *
 * <p>注意: 静态声明之外, 应用也可能在运行时 fork 出没有声明过的进程 (少见, 通常是 native 服务);
 * 那些进程只会在真正运行后出现在状态文件里. 界面会把「已声明」和「运行中」合并展示.
 */
object ProcessCatalog {
    /**
     * 返回应用的进程名列表: 主进程排第一, 其余按字母序.
     *
     * <p>拿不到 PackageInfo (应用被卸载 / 权限问题) 时至少返回主进程.
     */
    @Suppress("DEPRECATION")
    fun declaredProcesses(packageManager: PackageManager, packageName: String): List<String> {
        val names = LinkedHashSet<String>()
        names.add(packageName)

        try {
            val info = packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_ACTIVITIES or
                    PackageManager.GET_SERVICES or
                    PackageManager.GET_RECEIVERS or
                    PackageManager.GET_PROVIDERS
            )

            fun add(raw: String?) {
                val normalized = normalize(packageName, raw)
                if (normalized.isNotBlank())
                    names.add(normalized)
            }

            info.activities?.forEach { add(it.processName) }
            info.services?.forEach { add(it.processName) }
            info.receivers?.forEach { add(it.processName) }
            info.providers?.forEach { add(it.processName) }
            info.applicationInfo?.processName?.let { add(it) }
        } catch (t: Throwable) {
            // 应用可能刚被卸载 / 当前用户没有该应用: 忽略, 至少保留主进程.
        }

        return names.sortedWith(
            compareBy<String> { it != packageName }
                .thenBy { it }
        )
    }

    /**
     * 把 `android:process` 的值规范化成完整进程名.
     *
     * <ul>
     *     <li>null / 空串: 主进程 (包名);</li>
     *     <li>`:push`: 私有进程, 完整名是 `包名:push`;</li>
     *     <li>其它 (含 `.` 的完整名): 原样返回 (可能是共享进程).</li>
     * </ul>
     */
    fun normalize(packageName: String, raw: String?): String {
        val value = raw?.trim().orEmpty()
        return when {
            value.isEmpty() -> packageName
            value.startsWith(":") -> packageName + value
            else -> value
        }
    }
}
