package nep.timeline.cirno

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import nep.timeline.cirno.screen.MemoryScreen

/**
 * 内存回收 / 前台内存压制的设置界面.
 *
 * <p>从主界面左上角的齿轮进入. 所有开关都会写回
 * /data/misc/CirnoRecycle/GlobalSettings.json 的 memory 字段, system_server 2 秒内热重载.
 */
class MemoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                MemoryScreen(this)
            }
        }
    }
}
