package com.vezir.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.vezir.android.data.Prefs
import com.vezir.android.ui.RecordScreen
import com.vezir.android.ui.SetupScreen
import com.vezir.android.ui.theme.VezirTheme

/**
 * Single-activity host. The app has only three screens for v1 (Setup,
 * Record, Upload+Session). For M1 only Setup is real; Record and Upload are
 * stubs filled in by M2/M3.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VezirTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot()
                }
            }
        }
    }
}

private enum class Screen { Setup, Record }

@Composable
private fun AppRoot() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { Prefs(context) }

    var screen by remember {
        mutableStateOf(if (prefs.isConfigured()) Screen.Record else Screen.Setup)
    }

    // Re-evaluate screen on prefs change (Setup -> Record after enrollment).
    LaunchedEffect(Unit) {
        if (prefs.isConfigured()) screen = Screen.Record
    }

    when (screen) {
        Screen.Setup -> SetupScreen(
            prefs = prefs,
            onConfigured = { screen = Screen.Record },
        )
        Screen.Record -> RecordScreen(
            prefs = prefs,
            onSignOut = {
                prefs.clear()
                screen = Screen.Setup
            },
        )
    }
}
