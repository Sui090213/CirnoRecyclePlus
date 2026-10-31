package nep.timeline.cirno

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import nep.timeline.cirno.screen.ApplicationScreen
import nep.timeline.cirno.ui.CirnoTheme

class ApplicationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CirnoTheme {
                ApplicationScreen(this)
            }
        }
    }
}
