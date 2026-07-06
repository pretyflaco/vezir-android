# Changelog

Notable changes per release of the Vezir Android thin client.  Format
loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

Sideload-only.  APKs are attached to each GitHub release.  Same signing
keystore since v0.1.0; upgrades install in place.

> **Note (2026-05-24):** the upstream meeting-transcription pipeline
> was renamed `meetscribe` → `millet` (named after the Ottoman millet
> system) in vezir 0.4.0.  Existing Android sessions and the HTTP API
> are unaffected — the Android client talks to vezir's API only, never
> to the renamed Python packages directly.  Historical CHANGELOG
> entries below still mention `meetscribe` where that was the
> ecosystem name at the time of release; references are accurate as
> historical record.

## 0.8.0 — uploads that survive anything, permission fix, confidential by default

Hardening release from the 2026-07 ecosystem review.  Pairs with vezir
server ≥ 0.10.0 (grace-window benefits with ≥ 0.11.0: a refresh response
lost on a flaky link no longer kills the session server-side).

### Changed

- **Uploads survive backgrounding and process death.**  The upload now
  runs as foreground WorkManager work (dataSync type, with its own
  notification), network-constrained with exponential backoff.  The tus
  `upload_id` is persisted the moment the server session exists, so any
  retry — including a WorkManager reschedule after the OS killed the
  app — resumes from the server's byte offset instead of restarting
  from zero.  Previously the upload lived in a process-wide coroutine:
  Doze/app-standby/LMK could silently kill a multi-minute transfer with
  no retry and no error.
- **Confidential (TEE) is the default summarization preset**, as the
  README always documented.  The code shipped `high-quality` (Claude)
  as the default — privacy-relevant drift, now resolved in favor of the
  documented behavior.  Your explicit preset choice is unaffected.
- **3 h recording cap now counts recorded audio, not wall time.**  A
  meeting paused for an hour previously stopped after only 2 h of
  actual audio.

### Fixed

- **Denying the notification permission no longer blocks recording.**
  On Android 13+ the permission prompt asks for mic + notifications
  together; denying only notifications refused to record with a message
  that wrongly blamed RECORD_AUDIO.  Recording now requires only the
  mic; a notifications denial shows an accurate heads-up.
- **Token rotation mid-upload no longer costs a 401 per chunk.**  The
  uploaders read the current access token per request (token provider)
  instead of freezing it at construction.
- **Dead connections no longer hang the upload screen for hours.**
  Resumable chunks now have 2 min read / 5 min write budgets per 4 MiB
  chunk (was 30 min socket timeouts); the retry loop resumes from the
  server offset.
- **Flaky networks no longer silently downgrade to restart-from-zero.**
  The resumable-support probe treats an IOException as "assume
  supported" (a real 404/405 still falls back) and caches its answer
  per server instead of sending a junk zero-length create per upload.
- **Label-check worker is network-constrained** (no more offline
  wakeups burning probe timeouts every 15 min), and its notified-ids
  cap now evicts oldest-first — the previous unordered set could evict
  recent ids and re-notify sessions.
- **One `EncryptedSharedPreferences` instance process-wide.**  Three
  call sites each built their own instance over the same file (a
  documented corruption footgun) with repeated Keystore init, once on
  the main thread.
- **Release keystore moved out of the repo working tree** to
  `~/.android-keystores/vezir/` (never committed, but previously one
  `.gitignore` edit away from it).
- Shared OkHttp connection pool across all API classes
  (`HttpClients.build` now honors its documented contract).

## 0.7.0 — stay signed in (rotating refresh tokens)

Ends the daily forced logout.  As long as the app is used, the session
renews itself silently — no more re-approving in Amber / re-doing the
Google device flow every day.

Pairs with vezir server **≥ 0.10.0**, which now issues a short-lived
(60-minute) access token plus a rotating **refresh token** at login.

### Added

- **Silent token refresh.** On any `401`, an OkHttp `Authenticator`
  exchanges the stored refresh token at `POST /api/auth/refresh` for a
  fresh access token and retries the request once — transparently, across
  every screen (sessions, labeling, uploads, polling). Uploads also
  refresh proactively before starting, so a large upload never dies at
  100% on an expired token.
- **Single-flight refresh.** Concurrent 401s coalesce onto one refresh
  behind a mutex. Refresh tokens are single-use with server-side reuse
  detection, so a racing double-refresh would revoke the whole session —
  the mutex prevents that.
- **Multi-team aware.** A refresh updates every team entry that shares the
  session (one login = one session family across your teams).
- **Auto sign-in prompt.** When a refresh fails for real (no refresh
  token, or the server revoked/expired the session — 7-day idle / 30-day
  absolute cap), the app now routes you back to the login screen instead
  of showing a raw `401`.
- **`Sign out` now revokes server-side.** Signing out calls
  `POST /api/auth/logout` so the session can't be refreshed again, then
  clears local credentials.

### Notes

- **Backward compatible.** Against a server older than 0.10.0 (no refresh
  token issued), the app behaves exactly as 0.6.3 did — detect expiry,
  prompt to sign in again. Legacy `vzr_` tokens are unaffected.
- Refresh tokens are stored in the same EncryptedSharedPreferences as the
  access token and wiped on sign-out.

## 0.6.3 — handle expired session (no more silent 401 after upload)

Fixes the `HTTP 401` some users hit at 100% upload, plus a related silent
hang. The session JWT lasts 24h; nothing in the app noticed when it lapsed.

### Fixed

- **Expired session is detected before the upload starts.** If the JWT is
  expired (or within 5 min of it), the upload screen now says *"Session
  expired — please sign in again"* instead of streaming the whole file and
  failing with a raw `HTTP 401` at 100%. The `exp` claim is read on-device
  (no signature verification — that stays server-side).
- **Status polling no longer hangs forever on 401.** `SessionPoller`
  previously swallowed a 401 from `GET /api/sessions/{id}` and retried
  silently, leaving the screen stuck at 100% / `polling`. It now surfaces
  the expiry so you know to re-sign-in. *(This was the second failure mode
  behind the same root cause.)*
- **A 401 mid-upload** (resumable or legacy path) now shows the same
  friendly "sign in again" message rather than `HTTP 401: Unauthorized`.

### Notes

- No server change required — works against any vezir server **≥ 0.8.0**.
  Re-sign-in is a single Amber / Google prompt from the login screen; an
  upload that already reached the server keeps processing and its artifacts
  are reachable after re-login (Sessions tab / `vezir pull`).
- There is still no automatic token refresh; a future release may add a
  proactive re-auth prompt.

## 0.6.2 — Google sign-in: code ready to paste + clearer prompt

More polish on Google sign-in from on-device testing.

### Changed

- **The device code is now auto-copied to the clipboard** when Google
  sign-in starts, so if Google's page doesn't pre-fill it (or you land on
  the manual-entry page) it's already there to paste — no bouncing back to
  Vezir.  (The code card still shows it with a Copy button too.)
- **Clearer prompt.**  Google normally pre-fills the code and just asks you
  to tap **Continue** (it's a confirm step, not re-entry); the card now says
  so instead of implying you must type it.

### Notes

- Best with vezir server **0.8.3+** (device-flow DNS resilience: the
  occasional "could not reach Google to start sign-in" on the first tap is
  retried server-side).

## 0.6.1 — sign-in UX: signer chooser + pre-filled Google code

Polish on the 0.6.0 sign-in flows from on-device testing.

### Fixed

- **Amber sign-in now shows the system signer chooser.**  0.6.0 force-
  targeted the *first* installed NIP-55 signer, so a secondary signer
  (e.g. "nowser") could hijack the flow.  The `get_public_key` intent now
  launches with **no forced package**, so Android shows the picker; the
  signer the user selects (from the login result's `package`) is reused
  for the `sign_event` leg.
- **Google sign-in no longer requires typing the code.**  Using the
  server's new `verification_url_complete` (vezir 0.8.1), the app opens a
  **pre-filled** Google verification page.  The code is also shown in a
  card with **Copy code** / **Open page** buttons as a fallback (0.6.0
  buried it in non-copyable status text).

### Requires

- vezir server **0.8.1+** for the pre-filled Google URL
  (`verification_url_complete`).  Against 0.8.0 the app falls back to the
  bare verification URL + the (now copyable) code.

## 0.6.0 — nostr (Amber) + Google sign-in

Brings the app to parity with vezir 0.8.0's identity sign-in: instead of
pasting a `vzr_` token, members sign in with **Amber** (their Nostr
signer) or their **`@blinkbtc.com` Google account**.  Both yield a
short-lived session JWT that's used exactly like the old token
(`Authorization: Bearer`), so the entire networking layer is unchanged.

### Added

- **Sign in with Amber (NIP-55).**  A new `LoginScreen` launches the
  local Nostr signer via `nostrsigner:` Android intents — `get_public_key`
  then `sign_event`.  The app builds an unsigned **NIP-98** event (kind
  27235, `u`/`method` tags) and computes its id locally
  (`auth/Nip98Event.kt`, byte-identical to the server's canonical form);
  Amber Schnorr-signs it; the signed event is base64'd and POSTed to
  `/api/auth/nostr/login` (`auth/NostrLoginApi.kt`) for a session JWT.
  No private key ever touches the app.  Handles signers returning either
  the full signed event or just the 128-hex signature, and decodes an
  `npub` result to hex if needed (`auth/Bech32.kt`).
- **Sign in with Google (`@blinkbtc.com`).**  OAuth 2.0 Device
  Authorization Grant via vezir (`auth/GoogleLoginApi.kt`): the app shows
  the user a code, opens the verification URL in a browser, and polls
  `/api/auth/google/device/poll` until the server returns a JWT.  The
  OAuth client secret stays on the server; the app only polls vezir.
- **Unified post-login discovery** (`auth/SessionDiscovery.kt`): every
  sign-in path (Amber, Google, and the legacy token/QR enrollment) now
  funnels through one helper that calls `/api/me` and creates a
  `TeamCredential` per membership.

### Changed

- First-run and **Sign out** now land on the new `LoginScreen`; the old
  token/QR enrollment is kept as **Advanced: enter a token / scan QR**.
  `vzr_` tokens still work and are intended for machine/CI or advanced use.
- `AndroidManifest.xml`: added a `<queries>` block (`nostrsigner` + `https`
  schemes) for Android 11+/targetSdk-35 package visibility, so the app can
  detect/launch Amber and open the Google verification page.

### Requires

- vezir server **0.8.0+** (the `/api/auth/nostr/login` and
  `/api/auth/google/*` endpoints).  Token/QR enrollment still works
  against any server the previous client supported.

### Notes

- Sessions are short-lived (~24 h).  When a session expires, sign in
  again from **Settings → Sign out** (or add the team again); re-login
  with the same identity refreshes the stored token in place.  Automatic
  401→re-login routing is deferred to a later release.

## 0.5.3 — team UUID keys (slug-rename safe)

### Changed

- `/api/me` memberships now carry the team's stable **uuid** in
  `team_id` (sent back as `X-Team-Id`) plus an optional `slug` display
  field.  The app already keys `TeamCredential.id` on `team_id`, so a
  server-side team **slug rename** no longer orphans an enrolled team —
  the credential keeps working via the uuid and the display name
  refreshes on the next `/api/me`.

### Requires

- vezir server **0.7.4+** (UUID team keys + `vezir team rename`).
  Older servers send a slug in `team_id`; the app still works (it just
  keys on whatever `team_id` value the server returns).

## 0.5.2 — resumable uploads

### Added

- **Resumable upload** (`ResumableUploader.kt`) implementing vezir's
  tus.io 1.0 subset (`POST`/`HEAD`/`PATCH /upload/resumable`).  A
  dropped upload now resumes from the server's current offset (via a
  `HEAD` re-sync) instead of restarting at byte 0.  `SlicedUriBody`
  streams a byte-range of the content URI so a resumed `PATCH` skips to
  the resume offset without re-sending earlier bytes.
- `UploadController` prefers the resumable path and **falls back to the
  legacy one-shot `Uploader`** when the server doesn't expose the
  endpoints (`Outcome.Unsupported`), so older servers still work.

### Requires

- vezir server **0.7.3+** for the resumable endpoints.  Against older
  servers the client transparently uses the legacy `/upload` path.

## 0.5.1 — auto-discover new team memberships

### Fixed

* **Team picker now updates when memberships change server-side.**
  v0.5.0 created ``TeamCredential`` entries only at enrollment and
  on the legacy-migration path.  If the operator ran ``vezir team
  add-member`` to grant the user access to a new team, the picker
  did NOT show it -- the user had to kill+relaunch (and even then
  only the legacy migration path could discover memberships, which
  doesn't run once multi-team is configured).

  Now the existing background ``/api/me`` refresh in the sessions
  list (already wired for ``alternate_urls`` sync) also reconciles
  memberships against the local ``TeamCredentialStore``:

    * inserts new entries for memberships not yet stored (reusing
      the active credential's token + URL + caPem -- v0.7.0 tokens
      cover every team the user is in);
    * updates ``label`` (server-side team rename) and ``altUrls``
      on existing entries;
    * drops entries the server no longer reports (operator ran
      ``vezir team remove-member``).

  Triggers on every sessions-tab refresh; no kill+relaunch needed.
  The picker dropdown re-renders via a new ``onTeamsChanged``
  callback hoisted up to ``MainActivity``.

## 0.5.0 — vezir 0.7.0 compatibility (X-Team-Id + memberships)

This release tracks the breaking server change in vezir 0.7.0.  The
Android client now sends an ``X-Team-Id`` header on every team-scoped
request and consumes the new ``/api/me`` memberships array.  The HTML
dashboard handoff (browser open + exchange code) is removed.

**Required server version: vezir 0.7.0+.**  Older servers will
return 400 because they don't recognise the ``X-Team-Id`` header.
Pin to v0.4.4 if you're still on a 0.6.x server.

### Breaking changes

* **Drops the "Open in browser" action** on session detail and on the
  upload success screen.  The vezir 0.7.0 server no longer serves an
  HTML dashboard, so there's nothing to open.  The in-app session
  detail screen is now the canonical view.
* **/api/me response shape**: was ``{github, team_id, team_name,
  is_admin, alternate_urls}``; now ``{github, is_admin, memberships:
  [{team_id, team_name, role}, ...], alternate_urls}``.  Stored
  ``TeamCredential`` rows look the same to the rest of the app, but
  enrollment now creates one row per membership instead of one row
  total.
* **Upload response**: ``dashboard_url`` and ``dashboard_login_url``
  fields are no longer parsed (vezir 0.7.0 doesn't emit them).
  ``UploadController.Snapshot`` lost both fields too.
* **SessionApi.mintExchangeCode** removed; the ``/api/exchange-code``
  server endpoint is gone.

### Added

* **``HttpClients.authHeaders`` helper** that injects both
  ``Authorization`` and ``X-Team-Id`` headers on every request.
  Single source of truth for header construction across the 7 API
  classes (``SessionApi``, ``LabelApi``, ``MeApi``, ``Uploader``,
  ``SessionPoller``, ``AudioClipPlayer``, ``VezirApi``).
* **Multi-membership enrollment**: when ``/api/me`` returns >1
  membership the app creates one ``TeamCredential`` per team, all
  sharing the same token + URL.  The first is activated; the user
  can switch via the existing bottom-bar team picker.

### Changed

* ``VezirApi.checkToken`` now hits ``/api/me`` instead of
  ``/api/sessions``.  ``/api/sessions`` is team-scoped on vezir 0.7.0
  and would require an ``X-Team-Id`` header that we don't have at
  token-check time.
* ``Prefs.ActiveCredential`` gained a nullable ``id`` (team slug)
  field so screens can pass it through to ``HttpClients.authHeaders``.
* The legacy single-token path (``serverUrl`` + ``token`` in
  ``Prefs`` without a multi-team row) will hit 400s on any team-
  scoped request until ``/api/me`` discovery populates the
  ``TeamCredentialStore``.  This is the expected transitional state;
  the migration runs automatically on first launch after upgrading.

### Fixed

* ``EnrollmentPayloadTest.rejectsUnsupportedVersion`` was checking
  v=2 which is supported.  Switched to v=99.

## 0.4.1 — spectrometer polish + timezone fix

### Fixed

* **Spectrometer bars freeze** — replaced `FloatArray` with Compose
  `mutableStateListOf` so bar animations continue even when consecutive
  dBFS readings are identical (steady noise).
* **Pull directory timestamps use local timezone** — was UTC, causing
  mismatched folder names between local recordings and pulled sessions.
* **"Playback capture silent" warning** — shortened text, added dismiss
  button; resets on new recording.


## 0.4.0 — audio spectrometer + pull team meetings + exchange code fix

### Added

* **Audio level spectrometer** — graphical 12-bar waveform per channel
  (mic + system audio) on the Record screen, replacing the numeric
  dBFS readout.  Bars are drawn with a green gradient on Compose Canvas
  at ~10 Hz.  Cross-platform data contract matches desktop vezir 0.6.6.
* **Pull team meetings** — download meeting artifacts (summaries,
  transcripts, PDFs) for team sessions into `Documents/Vezir/<team>/`.
  Pull button (cloud download icon) added to the Sessions list header.
  Idempotent via a local manifest; shows progress during download.
* **Auto-download artifacts on upload completion** — when server
  processing reaches `done`, artifacts are automatically saved to
  `Documents/Vezir/<team>/meeting-YYYYMMDD-HHMMSS_TITLE/`.
* **`since` query parameter** on `getSessions()` API call — enables
  efficient incremental pulls.

### Fixed

* **"Open in browser" expired login** — now mints a fresh exchange
  code via `POST /api/exchange-code` instead of using the stale
  server URL that may prompt for a token.

### Changed

* Audio levels published at ~10 Hz (was 1 Hz) from CaptureService
  via a dedicated `AudioLevels` StateFlow on CaptureController.
* `SessionApi.getSessions()` accepts an optional `since` parameter.


## 0.2.5 — fix bottom nav occlusion, Actions menu on Record ([release](https://github.com/pretyflaco/vezir-android/releases/tag/v0.2.5))

### Fixed

* **Bottom nav bar occluded screen content.**  The Scaffold's `innerPadding`
  (which reserves space for the bottom navigation bar) was assigned to a
  local variable but never applied to the screen content.  The bottom
  ~80 dp of tab-root screens (Record, Sessions) was hidden behind the nav
  bar; the "Sign out" and "Import recording" buttons on the Record screen
  were effectively invisible.  Wrap all screen content in
  `Box(Modifier.padding(innerPadding))`.

### Changed

* **Actions overflow menu on Record screen.**  Replaced the bottom-row
  "Import recording" / "Sign out" TextButtons with an **Actions** overflow
  menu button (consistent with the SessionDetailScreen pattern from v0.2.4).
  Menu items: Import recording (disabled while recording), Sign out.

## 0.2.4 — actions overflow menu, retry-summary with preset picker ([release](https://github.com/pretyflaco/vezir-android/releases/tag/v0.2.4))

Server companion: vezir 0.1.17 + meetscribe 0.8.3.  Requires server
≥ 0.1.17 for preset override; degrades gracefully on older servers.

### Added

* **Actions overflow menu** on the session detail screen.  Single button
  exposes Retry summary (when `summary_error` set + status `done`), Label
  speakers / Re-label speakers (when `needs_labeling` / `done` / `error`),
  Sync now (when sync failed or sync disabled), Share with team (when
  personal), Open in browser (always).  "Label speakers" remains a
  prominent primary button when status is `needs_labeling`.

* **Retry summary with preset picker.**  Dialog presents three radio
  buttons (High Quality / Confidential / Alternative); default selection
  is the session's original preset.  Switching away from Confidential
  shows a warning: "Switching from Confidential to [preset] will send
  the transcript to a different provider."  Sends `{"preset": "..."}`
  JSON body to `POST /api/sessions/{id}/retry-summary`.

### Changed

* **Shared preset options.**  `PRESET_OPTIONS` and `presetLabelFor()`
  moved from `RecordScreen` to `Prefs` companion for reuse.

## 0.2.3 — green summarizing badge ([release](https://github.com/pretyflaco/vezir-android/releases/tag/v0.2.3))

Server companion: vezir 0.1.15.  Requires server ≥ 0.1.15 for
`summarizing` status; older servers never emit it.

### Added

* **Green `summarizing` badge.**  When the server is retrying summary
  generation (`POST /api/sessions/{id}/retry-summary`), the session
  status transitions through `summarizing` instead of `transcribing`.
  Rendered with a green badge (`#2E7D32`).  Older clients fall back to
  the default `surfaceVariant` color for unrecognized statuses.

## 0.2.2 — sync error UX, sync_error field ([release](https://github.com/pretyflaco/vezir-android/releases/tag/v0.2.2))

Server companion: vezir 0.1.14.  Requires server ≥ 0.1.14 for
`sync_error`; degrades gracefully on older servers.

### Added

* **Sync error UX.**
  - Session detail screen: shows "sync failed: artifacts are on the
    server but not pushed to git.  Use 'Sync now' to retry." when
    `sync_error` is set.
  - Upload screen: shows "sync failed (artifacts OK).  View session
    to retry."
  - "Sync now" button now also appears when `sync_error` is set, not
    just when `sync_enabled=0`.  Existing `POST /session/{id}/sync`
    endpoint handles the retry.

* **Data model**: `Session`, `SessionStatus`, and
  `UploadController.Snapshot` carry `sync_error` (ignored via
  `ignoreUnknownKeys` on servers < 0.1.14).

## 0.2.1 — graceful summary failures, retry button, default preset ([release](https://github.com/pretyflaco/vezir-android/releases/tag/v0.2.1))

Server companion: vezir 0.1.13.  Requires server ≥ 0.1.13 for
retry-summary; degrades gracefully on older servers.

### Changed

* **Default preset on fresh install: `high-quality`** (Claude Max /
  Sonnet 4.6) instead of `confidential` (Tinfoil TEE).  The Tinfoil
  backend depends on `atc.tinfoil.sh` being reachable at transcription
  time, which can fail after server restarts.  Users who previously
  selected a preset explicitly are unaffected.

### Added

* **Summary error UX.**
  - Upload screen: shows "summary unavailable (transcript OK).  View
    session to retry." instead of a raw server error when only the
    summary failed.
  - Session detail screen: shows a clear message when summary is
    unavailable, with transcript artifacts still listed and accessible.
  - **Retry summary button**: appears on session detail when a session
    has `summary_error`.  Taps `POST /api/sessions/{id}/retry-summary`
    and polls for completion.

* **Data model**: `Session`, `SessionStatus`, and
  `UploadController.Snapshot` carry `summary_error` (ignored via
  `ignoreUnknownKeys` on servers < 0.1.13).
  `SessionApi.retrySummary()` calls the new server endpoint.

## 0.2.0 — full native thin client ([release](https://github.com/pretyflaco/vezir-android/releases/tag/v0.2.0))

Major release.  The Android app is now a complete thin client — no
browser needed for any post-upload workflow.

### Added

#### New screens

* **Session list** (bottom nav "Sessions" tab): team sessions + your
  personal sessions.  Pull-to-refresh, status badges (colored by state),
  lock icon for personal recordings.
* **Session detail**: full metadata, artifact list (tap to view), action
  buttons (label / re-label / sync now / share with team / open in
  browser).  Auto-polls status while non-terminal.
* **Artifact viewer**: inline text viewer for `.txt` / `.srt` / `.json` /
  `.summary.md` (selectable / copyable).  PDFs open in the device's PDF
  app.  Share and download buttons on every artifact.

#### New features

* **Pause/resume recording.**  Pause button alongside stop during
  recording.  Audio is cleanly skipped (no silence gaps).  Timer shows
  recording time vs wall time.  Notification has pause/resume action.
* **Personal recording toggle.**  Per-session, sticky.  Personal
  sessions are hidden from other team members' lists and sync is forced
  off.  "Share with team" button on session detail to make it visible
  later.
* **Auto-delete after upload.**  Toggle in record screen settings;
  deletes the local OGG after the server confirms `done`.
* **Background labeling notification.**  Checks every 15 minutes for
  sessions needing labeling; fires a notification, tapping opens the app.
* **"View session" button** on upload screen — navigates to the native
  session detail instead of opening a browser.

#### Navigation

* Bottom nav bar: Record | Sessions.
* Proper back-stack: system back pops detail screens, then switches tabs,
  then exits.

#### Infrastructure

* FileProvider for sharing artifacts via Android share sheet.
* SessionApi: complete client for list / detail / sync / share / artifact /
  team endpoints.
* WorkManager periodic task for background labeling checks.

### Requirements

* Vezir server with personal-session support (shipped in the server
  commit accompanying this release).
* Re-enrol via QR if you haven't already (the v2 QR with embedded CA
  cert).

## 0.1.8 — fix labeling screen crash ([release](https://github.com/pretyflaco/vezir-android/releases/tag/v0.1.8))

### Fixed

* **Crash on opening labeling screen.**  The `channel` field in the
  speaker list JSON is a string (`"mic"`, `"system"`) from meetscribe,
  not an integer.  The deserializer was declared as `Int?` and crashed
  on the first character.  Fixed to `String?`.

* **Preventive: nested scrollable containers.**  `LabelScreen` used
  `ScreenScaffold` (which adds `verticalScroll`), but the speaker list
  is a `LazyColumn`.  Compose does not allow `LazyColumn` inside a
  vertically-scrollable `Column`.  Replaced with a custom fixed-height
  layout where `LazyColumn` gets the remaining space via `weight(1f)`.

## 0.1.7 — native speaker labeling screen ([release](https://github.com/pretyflaco/vezir-android/releases/tag/v0.1.7))

### Added

* **Native speaker labeling.**  When the server reports `needs_labeling`
  after upload, a "Label speakers" button appears on the upload screen.
  Tapping opens a native labeling screen where you can listen to audio
  clips for each speaker, assign names with team-handle autocomplete
  (dropdown populated from the server's `team.json`), and submit labels —
  the server regenerates all artifacts with the new names and syncs to
  git.  No browser needed for the labeling workflow anymore.

* Handles edge cases: audio deleted (clips unavailable), network errors
  during clip fetch, rate limiting (429), re-labeling already-done
  sessions.  "Open in dashboard" button demoted to secondary (still
  available as fallback).

### Requirements

* Vezir server with the JSON labeling endpoints (`GET/POST /api/label/{sessionId}`).
  Shipped in the server commit accompanying this release.  On older
  vezir, the "Label speakers" button won't appear; label via browser.

## 0.1.6 — v2 QR enrollment with embedded CA trust ([release](https://github.com/pretyflaco/vezir-android/releases/tag/v0.1.6))

### Added

* **QR payload v2 support.**  When the server (vezir 0.1.12+) is
  configured with `VEZIR_CADDY_ROOT_CERT_PATH`, the enrollment QR
  contains a `ca_pem` field with the Caddy internal CA certificate.
  The app parses, stores, and trusts this cert at the OkHttp layer — no
  manual cert install needed on the device.

* **All HTTP clients trust the embedded CA.**  VezirApi (health / token
  check), Uploader, and SessionPoller all use the custom trust manager
  when a CA cert is present.

### Changed

* **Cleartext HTTP removed.**  The hardcoded cleartext allow-list for
  muscle's IPs is gone.  HTTPS is now the only transport.  Re-enrol
  with a new QR to get the `https://` URL.

### Backward compatible

* v1 QR payloads (no `ca_pem`) work unchanged — OkHttp uses system trust
  only, same as 0.1.5.

## 0.1.5 — nostr-vpn cleartext allow-list ([release](https://github.com/pretyflaco/vezir-android/releases/tag/v0.1.5))

### Fixed

* **nostr-vpn cleartext allow-list entry.**  `network_security_config.xml`
  previously only listed Tailscale-style hosts (`muscle.tail178bd.ts.net`
  and `100.107.34.79`).  An upload over nvpn was rejected with
  `CLEARTEXT_NOT_PERMITTED` before the request reached the server.  Adds
  muscle's nvpn tunnel IP `10.44.141.239` to the allow-list so teammates
  can use the Android client over either VPN transport.

  Note: nostr-vpn does not ship a MagicDNS-equivalent, so the IP is the
  only addressable endpoint.  If the tunnel IP changes, both this file
  and the team onboarding docs need updating.

## 0.1.4 — auto-label + sync opt-out Switches ([release](https://github.com/pretyflaco/vezir-android/releases/tag/v0.1.4))

Mirrors the two per-upload privacy toggles shipped in vezir 0.1.11 on
the desktop.

### Added

Two new Switches on the record screen, both default ON:

* **Auto-label speakers.**  When off, the server skips voiceprint
  matching against the central DB and the session always routes to
  manual labeling.
* **Sync to git.**  When off, the session reaches `done (local-only)`
  on the dashboard.  Retroactively syncable by visiting the session
  detail page and tapping "Sync now".

Both are persisted in EncryptedSharedPreferences.  State is snapshotted
at upload-start so a mid-upload Switch flip doesn't affect the session
in flight.  Upload status panel now shows `auto-label=X  sync=Y`.

### Server compatibility

* Requires vezir server **≥ 0.1.11** to honor the opt-outs.  Against
  older servers, the form fields are sent but ignored.

## 0.1.3 — status-bar safe area ([release](https://github.com/pretyflaco/vezir-android/releases/tag/v0.1.3))

### Fixed

* **Status-bar safe area on every screen.**  `MainActivity` enables
  edge-to-edge rendering, but `ScreenScaffold` applied no `WindowInsets`.
  The vezir brand mark in the record screen header sat ~20 dp from the
  top and was overlapped by the system clock / battery indicators on
  phones with a tall status bar.  The fix consumes
  `WindowInsets.safeDrawing` inside `ScreenScaffold`, so every screen
  (Setup, QrScan, Record, Import, Upload) inherits the correct top and
  bottom safe area.

## 0.1.2 — summarization preset selector ([release](https://github.com/pretyflaco/vezir-android/releases/tag/v0.1.2))

First feature release on top of v0.1.1.  Adds the summarization preset
selector that ships in the desktop GTK/Tkinter clients (vezir 0.1.9) so
Android uploads can choose the same three backends.

### Added

* **Summarization preset dropdown** in the record screen, below the
  meeting-title field.  Three options: High Quality (Anthropic Sonnet
  4.6), Confidential (DeepSeek V4 Pro in the Tinfoil TEE — default on
  Android), Alternative (Kimi K2.6 via OpenRouter).
* **Sticky choice** in EncryptedSharedPreferences across launches.
* **Confidential default on first install**: a fresh enrollment opts
  into the hardware-attested TEE backend by default; you can override
  and the change sticks.
* **Live confirmation** on the upload screen — shows the selected
  preset id in the status block.

Sent to the vezir server as the multipart form field `summary_preset`.
Requires vezir server ≥ 0.1.9; older servers silently ignore the field.

### Changed

* AGP 8.5.2 → 8.7.3
* Kotlin 2.0.20 → 2.0.21
* Compose BOM 2024.10.01 → 2024.11.00
* `compileSdk` / `targetSdk` / `minSdk` unchanged at 35/35/29.

## 0.1.1 — branding polish ([release](https://github.com/pretyflaco/vezir-android/releases/tag/v0.1.1))

### Fixed

* **Launcher icon mark scaled down 30%** inside the adaptive icon
  viewport so circular and squircle launcher masks no longer clip the
  coral audio dot or the bottom of the plinth.

* **Cold-start splash now shows the stacked vezir lockup** (mark +
  wordmark) centered on a white background for 1.5 s before transitioning
  to the regular app content.  Replaces the brief mark-only OS splash
  with a proper brand moment.

## 0.1.0 — first public release ([release](https://github.com/pretyflaco/vezir-android/releases/tag/v0.1.0))

First public release of the Vezir Android thin client.

Sideload-only.  End-to-end validated against a real Blink dev-sync
sandbox session: phone records Google Meet via MediaProjection +
microphone, encodes OGG/Opus at 16 kHz mono / 24 kbps on-device, uploads
to a self-hosted Vezir server over Tailscale, and the server's worker
produces a usable transcript + summary.

### Capture matrix

| App | Status |
|---|---|
| Keet | works |
| Google Meet | works (validated 2026-04-29) |
| Signal call | blocked at the OS layer; falls back to mic-only |
| Zoom | likely works (untested) |
| Mic-only room recording | always works |

### Requirements

* Android 10 (API 29) or newer.
* Vezir server ≥ 0.1.2 with the `/admin/enroll` endpoint (Vezir token
  enroll convenience CLI requires the server-side commit `824fc37` or
  later).
* Tailscale or HTTPS reachability to the server.  Cleartext HTTP is
  allow-listed at build time per `network_security_config.xml`.

### Brand

* v0.1.0 ships brand integration: vizier-mark adaptive launcher icon
  (with Android 13 themed-icon variant), splash screen, Material3 theme
  keyed to the upstream brand tokens (`#111111` ink, `#FFFFFF` surface,
  `#FF6B35` coral), and a centered max-480 dp layout per screen.
