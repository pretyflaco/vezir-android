package com.vezir.android

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import android.net.Uri
import com.vezir.android.data.Prefs
import com.vezir.android.data.TeamCredentialStore
import com.vezir.android.ui.ArtifactViewerScreen
import com.vezir.android.ui.BottomNavBar
import com.vezir.android.ui.ImportScreen
import com.vezir.android.ui.LabelScreen
import com.vezir.android.ui.LoginScreen
import com.vezir.android.ui.QrScanScreen
import com.vezir.android.ui.RecordScreen
import com.vezir.android.ui.SessionDetailScreen
import com.vezir.android.ui.SessionListScreen
import com.vezir.android.ui.SettingsScreen
import com.vezir.android.ui.SetupScreen
import com.vezir.android.ui.SplashScreen
import com.vezir.android.ui.Tab
import com.vezir.android.ui.UploadScreen
import com.vezir.android.ui.theme.VezirTheme
import kotlinx.coroutines.launch

/**
 * Single-activity host with a manual navigation stack and bottom nav.
 *
 * Three tabs: Record (mic icon), Sessions (list icon), Settings (gear).
 * Detail screens (Upload, Label, SessionDetail, ArtifactViewer) push
 * onto the stack. System back pops; at a tab root, switches to Record;
 * at Record root, exits the app.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Wire the shared token refresher before any UI/network so the
        // OkHttp Authenticator can rotate sessions on a 401.
        com.vezir.android.auth.TokenRefresher.init(
            TeamCredentialStore(Prefs.get(applicationContext)),
        )
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

private const val DEFAULT_SERVER_URL = "https://vezir.twentyone.ist"

private sealed class Screen {
    object Splash : Screen()
    object Login : Screen()
    object Setup : Screen()
    object QrScan : Screen()
    object Record : Screen()
    object Import : Screen()
    object SessionList : Screen()
    object Settings : Screen()
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
    val prefs = remember { Prefs.get(context) }
    val teamStore = remember { TeamCredentialStore(prefs) }
    val scope = rememberCoroutineScope()

    // Navigation state.
    val stack = remember { mutableStateListOf<Screen>(Screen.Splash) }
    var currentTab by remember { mutableStateOf(Tab.Record) }

    // Team state — refreshed on launch and after switches.
    var activeTeamLabel by remember { mutableStateOf<String?>(null) }
    var teams by remember { mutableStateOf(teamStore.loadAll()) }

    // On first composition, attempt migration + /api/me fetch.
    LaunchedEffect(Unit) {
        // Legacy -> multi-team migration (blocking, ~5s timeout).
        // v0.5.0: /api/me now returns a memberships array (vezir 0.7.0+).
        // We create one TeamCredential per membership, all sharing the
        // same token + URL, and activate the first one.
        if (!prefs.hasTeamCredentials() && prefs.hasLegacyCredentials()) {
            val url = prefs.serverUrl ?: return@LaunchedEffect
            val token = prefs.token ?: return@LaunchedEffect
            val caPem = prefs.caPem
            try {
                val outcome = com.vezir.android.auth.SessionDiscovery
                    .discoverAndStore(teamStore, url, token, caPem)
                if (outcome.teamCount > 0) {
                    prefs.clearLegacyCredentials()
                    activeTeamLabel = outcome.activeLabel
                    teams = teamStore.loadAll()
                    Log.i("Vezir", "Migrated legacy credentials to ${outcome.teamCount} team(s)")
                } else {
                    Log.w("Vezir", "Migration deferred: no memberships / /api/me failed")
                }
            } catch (e: Exception) {
                Log.w("Vezir", "Migration deferred: ${e.message}")
            }
        }

        // Refresh active team label from stored credentials.
        val active = teamStore.getActive()
        if (active != null) {
            activeTeamLabel = active.label.ifBlank { active.id }
        }
        teams = teamStore.loadAll()
    }

    val currentScreen = stack.lastOrNull() ?: Screen.Splash

    // Whether to show the bottom nav bar (only on tab-root screens).
    val showBottomBar = currentScreen is Screen.Record ||
        currentScreen is Screen.SessionList ||
        currentScreen is Screen.Settings

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

    // Route to the login screen when a token refresh fails definitively
    // (no refresh token, or the server revoked/expired the session).  Set
    // by TokenRefresher via the OkHttp Authenticator or the proactive
    // upload check.  Clears itself so a later re-login starts fresh.
    val sessionExpired by com.vezir.android.auth.AuthState.sessionExpired.collectAsState()
    LaunchedEffect(sessionExpired) {
        if (sessionExpired) {
            val current = stack.lastOrNull()
            if (current !is Screen.Login &&
                current !is Screen.Setup &&
                current !is Screen.QrScan &&
                current !is Screen.Splash
            ) {
                stack.clear()
                stack.add(Screen.Login)
                currentTab = Tab.Record
            }
            com.vezir.android.auth.AuthState.clear()
        }
    }

    fun switchToTeam(teamId: String) {
        teamStore.setActiveId(teamId)
        val team = teamStore.getActive()
        activeTeamLabel = team?.label?.ifBlank { team.id }
        // Reload current tab to reflect new team.
        if (currentTab == Tab.Sessions) {
            stack.clear()
            stack.add(Screen.SessionList)
        }
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
                                Tab.Settings -> Screen.Settings
                            },
                        )
                    },
                )
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
        when (val s = currentScreen) {
            Screen.Splash -> SplashScreen(
                onDone = {
                    replaceTop(
                        if (prefs.isConfigured()) Screen.Record else Screen.Login,
                    )
                },
            )
            Screen.Login -> LoginScreen(
                defaultUrl = teamStore.getActive()?.url ?: DEFAULT_SERVER_URL,
                onLoggedIn = { url, jwt, refreshToken, accessExpiresIn ->
                    scope.launch {
                        val outcome = com.vezir.android.auth.SessionDiscovery
                            .discoverAndStore(
                                teamStore, url, jwt, caPem = null,
                                refreshToken = refreshToken,
                                accessExpiresIn = accessExpiresIn,
                            )
                        if (outcome.teamCount > 0) {
                            prefs.clearLegacyCredentials()
                            activeTeamLabel = outcome.activeLabel
                            teams = teamStore.loadAll()
                            replaceTop(Screen.Record)
                        }
                        // If no memberships, LoginScreen keeps its own status;
                        // stay on Login so the user can retry / contact admin.
                    }
                },
                onUseToken = { push(Screen.Setup) },
            )
            Screen.Setup -> SetupScreen(
                prefs = prefs,
                onConfigured = {
                    // After token/QR enrollment, discover team memberships
                    // via /api/me using the shared SessionDiscovery helper
                    // (same path the Amber/Google sign-in flows use).
                    scope.launch {
                        val url = prefs.serverUrl
                        val token = prefs.token
                        val caPem = prefs.caPem
                        if (url != null && token != null) {
                            val outcome = com.vezir.android.auth.SessionDiscovery
                                .discoverAndStore(teamStore, url, token, caPem)
                            if (outcome.teamCount > 0) {
                                prefs.clearLegacyCredentials()
                                activeTeamLabel = outcome.activeLabel
                                teams = teamStore.loadAll()
                            }
                        }
                    }
                    replaceTop(Screen.Record)
                },
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
                teamLabel = activeTeamLabel,
                teams = teams,
                activeTeamId = teamStore.activeId(),
                onSwitchTeam = { switchToTeam(it) },
                onUpload = { uri, name, title, preset, autoLabel, sync ->
                    push(
                        Screen.Upload(
                            uri, name, title, preset, autoLabel, sync,
                            personal = prefs.personal,
                        ),
                    )
                },
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
                teamLabel = activeTeamLabel,
                teams = teams,
                activeTeamId = teamStore.activeId(),
                onSwitchTeam = { switchToTeam(it) },
                onTeamsChanged = {
                    // /api/me discovered new/removed memberships; reload
                    // the team list so the picker updates without a kill+
                    // relaunch.  The active team itself may also have
                    // been renamed, so refresh the label too.
                    teams = teamStore.loadAll()
                    val active = teamStore.getActive()
                    activeTeamLabel = active?.label?.ifBlank { active.id }
                },
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
            Screen.Settings -> SettingsScreen(
                prefs = prefs,
                teamStore = teamStore,
                activeTeamLabel = activeTeamLabel,
                onImport = { push(Screen.Import) },
                onSignOut = {
                    // Best-effort server-side session revocation before we
                    // drop the local credential (server >= 0.10.0).
                    val active = teamStore.getActive()
                    if (active != null) {
                        scope.launch {
                            runCatching {
                                com.vezir.android.net.LogoutApi(
                                    active.url, active.token, active.caPem,
                                ).logout()
                            }
                        }
                    }
                    com.vezir.android.auth.AuthState.clear()
                    prefs.clear()
                    replaceTop(Screen.Login)
                },
                onAddTeam = {
                    push(Screen.Setup)
                },
                onSwitchTeam = { teamId -> switchToTeam(teamId) },
            )
        } // when
        } // Box
    } // Scaffold
} // AppRoot
