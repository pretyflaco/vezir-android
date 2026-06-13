package com.vezir.android.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vezir.android.auth.AmberSigner
import com.vezir.android.auth.GoogleLoginApi
import com.vezir.android.auth.Nip98Event
import com.vezir.android.auth.NostrLoginApi
import kotlinx.coroutines.launch

/**
 * Sign-in screen (vezir 0.6.0).  Three paths, all converging on a bearer
 * token the caller persists via [onLoggedIn]:
 *
 *   1. **Amber (Nostr signer)** — NIP-55 foreground intents: get the
 *      pubkey, build a NIP-98 event, have Amber sign it, POST it to
 *      `/api/auth/nostr/login` for a session JWT.
 *   2. **Google (@blinkbtc.com)** — OAuth device-code via vezir: show the
 *      user a code + open the verification URL, poll for the JWT.
 *   3. **Token / QR** — the legacy advanced path ([onUseToken] → SetupScreen).
 *
 * [defaultUrl] pre-fills the server (the public host).  On success the
 * screen calls [onLoggedIn] with the resolved (url, jwt); MainActivity
 * runs `/api/me` team discovery from there.
 */
@Composable
fun LoginScreen(
    defaultUrl: String,
    onLoggedIn: (url: String, jwt: String) -> Unit,
    onUseToken: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf(defaultUrl) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Amber state: pubkey + signer package captured at login, then a
    // pending unsigned event awaiting the sign launcher's result.
    var signerPackage by remember { mutableStateOf<String?>(null) }
    var pendingUnsigned by remember { mutableStateOf<Nip98Event.Unsigned?>(null) }

    // Google device-code state, shown in a copyable card while polling.
    var googleCode by remember { mutableStateOf<String?>(null) }
    var googleVerifyUrl by remember { mutableStateOf<String?>(null) }

    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    fun openUrl(u: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun postSignedEvent(signedJson: String) {
        scope.launch {
            busy = true
            status = "Verifying with vezir…"
            when (val r = NostrLoginApi(url).login(signedJson)) {
                is NostrLoginApi.Result.Ok -> {
                    status = "Signed in as ${r.data.github}."
                    onLoggedIn(url, r.data.session_jwt)
                }
                is NostrLoginApi.Result.HttpError ->
                    status = "Rejected (${r.code}): ${r.message}"
                is NostrLoginApi.Result.NetworkError ->
                    status = "Network error: ${r.cause.message}"
            }
            busy = false
        }
    }

    // Launcher for the sign_event round-trip.
    val signLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val unsigned = pendingUnsigned
        if (result.resultCode != Activity.RESULT_OK || unsigned == null) {
            status = "Signing was cancelled."
            busy = false
            return@rememberLauncherForActivityResult
        }
        when (val s = AmberSigner.parseSign(result.data, unsigned)) {
            is AmberSigner.SignResult.Success -> postSignedEvent(s.signedEventJson)
            AmberSigner.SignResult.Rejected -> {
                status = "Signing was rejected in Amber."
                busy = false
            }
            is AmberSigner.SignResult.Failed -> {
                status = "Signer error: ${s.reason}"
                busy = false
            }
        }
    }

    // Launcher for the get_public_key (login) round-trip.
    val loginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            status = "Amber sign-in was cancelled."
            busy = false
            return@rememberLauncherForActivityResult
        }
        when (val login = AmberSigner.parseLogin(result.data)) {
            is AmberSigner.LoginResult.Success -> {
                signerPackage = login.signerPackage
                val unsigned = Nip98Event.buildLogin(
                    pubkeyHex = login.pubkeyHex,
                    loginUrl = NostrLoginApi.loginUrl(url),
                )
                pendingUnsigned = unsigned
                status = "Approve the signature in Amber…"
                signLauncher.launch(
                    AmberSigner.signIntent(unsigned, login.pubkeyHex, login.signerPackage),
                )
            }
            AmberSigner.LoginResult.Rejected -> {
                status = "Amber sign-in was rejected."
                busy = false
            }
            is AmberSigner.LoginResult.Failed -> {
                status = "Amber error: ${login.reason}"
                busy = false
            }
        }
    }

    fun startAmber() {
        if (url.isBlank()) { status = "Enter the server URL first."; return }
        if (!AmberSigner.isSignerInstalled(context)) {
            status = "No Nostr signer found. Install Amber, then try again."
            return
        }
        busy = true
        status = "Choose your Nostr signer…"
        // No forced package: let Android show the system chooser among all
        // installed NIP-55 signers (Amber, etc.).  The signer the user picks
        // is captured from the login result's `package` extra and reused for
        // the sign_event leg, so the whole flow stays with their choice.
        loginLauncher.launch(AmberSigner.loginIntent(signerPackage = null))
    }

    fun startGoogle() {
        if (url.isBlank()) { status = "Enter the server URL first."; return }
        scope.launch {
            busy = true
            status = "Starting Google sign-in…"
            val api = GoogleLoginApi(url)
            val cfg = api.fetchConfig()
            if (cfg !is GoogleLoginApi.Result.Ok || !cfg.data.configured) {
                status = "Google sign-in isn't enabled on this server."
                busy = false
                return@launch
            }
            when (val start = api.deviceStart()) {
                is GoogleLoginApi.Result.Ok -> {
                    val d = start.data
                    // Prefer the complete URL (code embedded) so the page is
                    // pre-filled; keep the bare URL for the "Open page" button
                    // and the displayed code as a fallback.
                    val bareUrl = d.verification_url ?: "https://www.google.com/device"
                    val openUrlTarget = d.verification_url_complete ?: bareUrl
                    googleCode = d.user_code
                    googleVerifyUrl = bareUrl
                    // Pre-copy the code to the clipboard so that if Google's
                    // page doesn't pre-fill it (or the user lands on the
                    // manual-entry page), it's ready to paste — no bouncing
                    // back to vezir.  (The card also shows it with a Copy
                    // button.)
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(d.user_code))
                    status = "Code copied. Approve in the browser as your @blinkbtc.com account…"
                    openUrl(openUrlTarget)
                    when (
                        val poll = api.pollUntilDone(
                            deviceCode = d.device_code,
                            intervalSec = d.interval,
                            onPending = { },
                        )
                    ) {
                        is GoogleLoginApi.Result.Ok -> {
                            googleCode = null
                            status = "Signed in as ${poll.data.github} (${poll.data.email})."
                            onLoggedIn(url, poll.data.session_jwt)
                        }
                        is GoogleLoginApi.Result.HttpError -> {
                            googleCode = null
                            status = "Google sign-in failed (${poll.code}): ${poll.message}"
                        }
                        is GoogleLoginApi.Result.NetworkError -> {
                            googleCode = null
                            status = "Network error: ${poll.cause.message}"
                        }
                    }
                }
                is GoogleLoginApi.Result.HttpError ->
                    status = "Could not start Google sign-in (${start.code}): ${start.message}"
                is GoogleLoginApi.Result.NetworkError ->
                    status = "Network error: ${start.cause.message}"
            }
            busy = false
        }
    }

    ScreenScaffold {
        BrandHeader(subtitle = "sign in")

        Text(
            "Sign in to your Vezir server. Use Amber if you have a Nostr key, " +
                "or your @blinkbtc.com Google account.",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = url,
            onValueChange = { url = it.trim() },
            label = { Text("Server URL") },
            placeholder = { Text("https://vezir.twentyone.ist") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = { startAmber() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Sign in with Amber") }

        Button(
            onClick = { startGoogle() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Sign in with Google") }

        // While a Google device code is live, show it prominently with
        // copy + open-page actions.  Google normally pre-fills the code and
        // just asks you to tap Continue; this card (and the auto-copied code)
        // is the fallback if it asks you to enter or confirm it.
        val code = googleCode
        if (code != null) {
            androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Google should fill this code in for you — just tap " +
                            "Continue. If it asks, the code is copied and ready " +
                            "to paste:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        code,
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(code))
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Copy code") }
                        OutlinedButton(
                            onClick = { googleVerifyUrl?.let { openUrl(it) } },
                            modifier = Modifier.weight(1f),
                        ) { Text("Open page") }
                    }
                }
            }
        }

        HorizontalDivider()

        TextButton(
            onClick = onUseToken,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Advanced: enter a token / scan QR") }

        if (status != null) {
            MonoStatus(status!!)
        }
    }
}
