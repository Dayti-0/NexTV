package tv.nextv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import tv.nextv.app.ui.NexTvNavHost
import tv.nextv.app.ui.theme.NexTvTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NexTvTheme {
                Surface {
                    NexTvNavHost()
                }
            }
        }
    }
}
