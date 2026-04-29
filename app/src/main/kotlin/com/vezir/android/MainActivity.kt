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
import android.net.Uri
import com.vezir.android.data.Prefs
import com.vezir.android.ui.RecordScreen
import com.vezir.android.ui.SetupScreen
import com.vezir.android.ui.UploadScreen
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

private sealed class Screen {
    object Setup : Screen()
    object Record : Screen()
    data class Upload(val uri: Uri, val fileName: String, val title: String?) : Screen()
}

@Composable
private fun AppRoot() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { Prefs(context) }

    var screen by remember {
        mutableStateOf<Screen>(if (prefs.isConfigured()) Screen.Record else Screen.Setup)
    }

    LaunchedEffect(Unit) {
        if (prefs.isConfigured() && screen is Screen.Setup) screen = Screen.Record
    }

    when (val s = screen) {
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
            onUpload = { uri, name, title -> screen = Screen.Upload(uri, name, title) },
        )
        is Screen.Upload -> UploadScreen(
            prefs = prefs,
            contentUri = s.uri,
            fileName = s.fileName,
            title = s.title,
            onDismiss = { screen = Screen.Record },
        )
    }
}
