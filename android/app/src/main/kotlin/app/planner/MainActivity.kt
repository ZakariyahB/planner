package app.planner

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.planner.data.ThemeRepository
import app.planner.routines.RoutineLaunch
import app.planner.ui.PlannerRoot
import app.planner.ui.theme.PlannerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var themeRepository: ThemeRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        RoutineLaunch.handle(intent)
        // Read once before the first frame so a chosen theme never flashes the other one at launch.
        val firstMode = runBlocking(Dispatchers.IO) { themeRepository.current() }
        setContent {
            val mode by themeRepository.mode.collectAsStateWithLifecycle(initialValue = firstMode)
            val dark = mode.isDark(isSystemInDarkTheme())
            // The status and navigation bar icons must contrast with the chosen theme, not the phone's.
            DisposableEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                this@MainActivity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose {}
            }
            PlannerTheme(darkTheme = dark) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PlannerRoot()
                }
            }
        }
    }

    // A notification tapped while the app is open arrives here (see RoutineLaunch.intent).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        RoutineLaunch.handle(intent)
    }
}
