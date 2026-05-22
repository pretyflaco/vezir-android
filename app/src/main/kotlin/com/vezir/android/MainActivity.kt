package com.vezir.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import android.net.Uri
import com.vezir.android.data.Prefs
import com.vezir.android.ui.ArtifactViewerScreen
import com.vezir.android.ui.BottomNavBar
import com.vezir.android.ui.ImportScreen
import com.vezir.android.ui.LabelScreen
import com.vezir.android.ui.QrScanScreen
import com.vezir.android.ui.RecordScreen
import com.vezir.android.ui.SessionDetailScreen
import com.vezir.android.ui.SessionListScreen
import com.vezir.android.ui.SetupScreen
import com.vezir.android.ui.SplashScreen
import com.vezir.android.ui.Tab
import com.vezir.android.ui.UploadScreen
import com.vezir.android.ui.theme.VezirTheme

/**
 * Single-activity host with a manual navigation stack and bottom nav.
 *
 * Two tabs: Record (mic icon) and Sessions (list icon).
 * Detail screens (Upload, Label, SessionDetail, ArtifactViewer) push
 * onto the stack. System back pops; at a tab root, switches to Record;
 * at Record root, exits the app.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
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
    object Splash : Screen()
    object Setup : Screen()
    object QrScan : Screen()
    object Record : Screen()
    object Import : Screen()
    object SessionList : Screen()
    data class Upload(
        val uri: Uri,
        val fileName: String,
        val title: String?,
        val summaryPreset: String?,
        val autoLabel: Boolean,
        val sync: Boolean,
        val personal: Boolean,
    ) : Screen()
    data class Label(val sessionId: String) : Screen()
    data class SessionDetail(val sessionId: String) : Screen()
    data class ArtifactView(val sessionId: String, val artifactName: String) : Screen()
}

@Composable
private fun AppRoot() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { Prefs(context) }

    // Navigation state.
    val stack = remember { mutableStateListOf<Screen>(Screen.Splash) }
    var currentTab by remember { mutableStateOf(Tab.Record) }

    val currentScreen = stack.lastOrNull() ?: Screen.Splash

    // Whether to show the bottom nav bar (only on tab-root screens).
    val showBottomBar = currentScreen is Screen.Record ||
        currentScreen is Screen.SessionList

    // Back handler: pop stack, or switch to Record tab, or let system handle.
    BackHandler(enabled = stack.size > 1 || currentTab != Tab.Record) {
        if (stack.size > 1) {
            stack.removeLastOrNull()
        } else if (currentTab != Tab.Record) {
            currentTab = Tab.Record
            stack.clear()
            stack.add(Screen.Record)
        }
    }

    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeLastOrNull() }
    fun replaceTop(s: Screen) {
        if (stack.isNotEmpty()) stack.removeLastOrNull()
        stack.add(s)
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                BottomNavBar(
                    selected = currentTab,
                    onSelect = { tab ->
                        currentTab = tab
                        stack.clear()
                        stack.add(
                            when (tab) {
                                Tab.Record -> Screen.Record
                                Tab.Sessions -> Screen.SessionList
                            },
                        )
                    },
                )
            }
        },
    ) { innerPadding ->
        val modifier = Modifier.padding(innerPadding)

        when (val s = currentScreen) {
            Screen.Splash -> SplashScreen(
                onDone = {
                    replaceTop(
                        if (prefs.isConfigured()) Screen.Record else Screen.Setup,
                    )
                },
            )
            Screen.Setup -> SetupScreen(
                prefs = prefs,
                onConfigured = { replaceTop(Screen.Record) },
                onScanQr = { push(Screen.QrScan) },
            )
            Screen.QrScan -> QrScanScreen(
                onScanned = { payload ->
                    prefs.serverUrl = payload.url
                    prefs.token = payload.token
                    prefs.caPem = payload.ca_pem
                    pop()  // back to Setup with prefs populated
                },
                onCancel = { pop() },
            )
            Screen.Record -> RecordScreen(
                prefs = prefs,
                onSignOut = {
                    prefs.clear()
                    replaceTop(Screen.Setup)
                },
                onUpload = { uri, name, title, preset, autoLabel, sync ->
                    push(
                        Screen.Upload(
                            uri, name, title, preset, autoLabel, sync,
                            personal = prefs.personal,
                        ),
                    )
                },
                onImport = { push(Screen.Import) },
            )
            Screen.Import -> ImportScreen(
                onCancel = { pop() },
                onImported = { uri, name ->
                    replaceTop(
                        Screen.Upload(
                            uri, name, null,
                            prefs.summaryPreset, prefs.autoLabel, prefs.sync,
                            personal = prefs.personal,
                        ),
                    )
                },
            )
            is Screen.Upload -> UploadScreen(
                prefs = prefs,
                contentUri = s.uri,
                fileName = s.fileName,
                title = s.title,
                summaryPreset = s.summaryPreset,
                autoLabel = s.autoLabel,
                sync = s.sync,
                personal = s.personal,
                onDismiss = { pop() },
                onLabel = { sessionId -> push(Screen.Label(sessionId)) },
                onSessionDetail = { sessionId ->
                    // Replace upload screen with session detail so back
                    // from detail goes to Record, not back to upload.
                    replaceTop(Screen.SessionDetail(sessionId))
                },
            )
            is Screen.Label -> LabelScreen(
                prefs = prefs,
                sessionId = s.sessionId,
                onDone = { pop() },
                onCancel = { pop() },
            )
            Screen.SessionList -> SessionListScreen(
                prefs = prefs,
                onSessionClick = { sessionId ->
                    push(Screen.SessionDetail(sessionId))
                },
            )
            is Screen.SessionDetail -> SessionDetailScreen(
                prefs = prefs,
                sessionId = s.sessionId,
                onBack = { pop() },
                onLabel = { sessionId -> push(Screen.Label(sessionId)) },
                onArtifact = { sessionId, name ->
                    push(Screen.ArtifactView(sessionId, name))
                },
            )
            is Screen.ArtifactView -> ArtifactViewerScreen(
                prefs = prefs,
                sessionId = s.sessionId,
                artifactName = s.artifactName,
                onBack = { pop() },
            )
        }
    }
}
