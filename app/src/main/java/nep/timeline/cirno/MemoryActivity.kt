package nep.timeline.cirno

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import nep.timeline.cirno.screen.SettingsScreen
import nep.timeline.cirno.ui.AppBackground
import nep.timeline.cirno.ui.CirnoTheme

/**
 * 设置界面: 外观 / 通用 / 内存回收 / 前台内存压制.
 *
 * <p>从主界面左上角的齿轮进入. 内存相关开关会写回
 * /data/misc/CirnoRecycle/GlobalSettings.json 的 memory 字段, system_server 2 秒内热重载;
 * 外观 / 通用设置只影响 App 自己, 存在 SharedPreferences 里.
 */
class MemoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CirnoTheme {
                AppBackground {
                    SettingsScreen()
                }
            }
        }
    }
}
