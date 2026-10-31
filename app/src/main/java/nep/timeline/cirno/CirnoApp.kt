package nep.timeline.cirno

import android.app.Application
import nep.timeline.cirno.client.AppPrefs

/**
 * App 进程入口: 初始化界面偏好 (主题 / 更新检查开关等).
 *
 * <p>只影响 App 自己的进程; LSPosed 注入到 system_server 的模块不会创建这个 Application.
 */
class CirnoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppPrefs.init(this)
    }
}
