<p align="left">
  <picture>
    <source media="(prefers-color-scheme: dark)"
            srcset="https://raw.githubusercontent.com/pretyflaco/vezir/main/assets/logo/vezir-logo-light.svg">
    <img src="https://raw.githubusercontent.com/pretyflaco/vezir/main/assets/logo/vezir-logo.svg"
         alt="vezir" width="320">
  </picture>
</p>

# vezir-android

Android client for [Vezir](https://github.com/pretyflaco/vezir) —
self-hosted team intelligence. Record a meeting on the phone (system audio
+ microphone), encode to OGG/Opus on-device, and upload to your Vezir
server over plain HTTPS. The server transcribes, diarizes, summarizes,
labels speakers, and syncs to your team archive. Sign in with **Nostr** or
**Google** — no VPN, no token pasting.

## Status

Alpha (**0.6.3**). Sideload only; no Play Store. Full history in
[`CHANGELOG.md`](CHANGELOG.md).

## Sign in

On first launch, point the app at your server (e.g.
`https://vezir.twentyone.ist`) and pick one:

- **Sign in with Nostr** — uses a local NIP-55 signer
  ([Amber](https://github.com/greenart7c3/Amber/releases)) holding your
  key. The app shows the system signer chooser; approve `get_public_key`
  then the login signature in Amber. Your key never touches the app.
- **Sign in with Google** — OAuth device flow for a `@workspace-domain`
  account: the app shows a code and opens Google in a browser (usually
  pre-filled — just tap Continue). The code is also copied to your
  clipboard as a fallback.

Either way the server mints a short-lived **session JWT** (~24h), stored in
`EncryptedSharedPreferences`. An admin must authorize your `npub` / email
on the server first (`vezir npub add` / `vezir google add`); you'll then
auto-discover every team you belong to and can switch between them in a
dropdown.

> "Advanced: enter a token / scan QR" remains for machine/CI or legacy
> `vzr_`-token use.

## What it does

| Action | How |
|---|---|
| Sign in | Nostr (Amber) or Google, per above. |
| Record meeting | **Start recording** → Android `MediaProjection` consent → captures system playback + mic, mixes with soft-clip, encodes Opus. 3h hard cap. |
| Save | OGG lands in `Music/Vezir/vezir-<timestamp>.ogg`. |
| Summarization preset | Dropdown: **High Quality** (Sonnet), **Confidential** (TEE; default on Android), **Alternative**. Sent as `summary_preset`. |
| Auto-label / Sync / Personal | Switches on the record screen (sticky, except Personal which resets per launch), mirroring the desktop toggles. |
| Upload | Resumable multipart upload with progress; polls `/api/sessions/{id}` to completion. |
| Browse | Sessions tab: status, transcripts, summaries, artifacts. |
| Import existing recording | SAF picker → decode → resample → Opus. Screen recordings, voice memos, prior Vezir OGGs all work. |

## Requirements

- Android 10 (API 29)+.
- A reachable Vezir server, **≥ 0.8.0** (for the Nostr/Google sign-in
  endpoints). 0.8.3+ recommended (Google device-flow DNS resilience).
- Your identity authorized on the server (`npub` or `@domain` email) and a
  team membership — ask your operator.
- For Nostr sign-in: a NIP-55 signer
  ([Amber](https://github.com/greenart7c3/Amber/releases)) on the phone
  holding your key.
- **No VPN.** The server is reached over ordinary HTTPS.

## Install

The signed APK is attached to each [GitHub Release](https://github.com/pretyflaco/vezir-android/releases/latest).

```bash
adb install -r vezir-android-0.6.3.apk
```

Or open the APK in your file manager and allow install from "unknown
sources". Releases use the same signing key, so upgrades install in place.

## Build

```bash
./gradlew assembleDebug         # debug APK
./gradlew assembleRelease       # signed release (see Signing)
./gradlew testDebugUnitTest     # JVM unit tests (no emulator)
./gradlew lintDebug
```

Build host: JDK 17, Android SDK with `platforms/android-35` +
`build-tools/35.0.0`.

### Signing

The release config reads `keystore.properties` (gitignored) next to
`build.gradle.kts`:

```properties
storeFile=/absolute/path/to/vezir-release.jks
storePassword=...
keyAlias=vezir
keyPassword=...
```

Without it, `assembleRelease` falls back to the debug keystore so CI/clones
still build. Generate a keystore once with `keytool -genkey -v -keystore
vezir-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias vezir`.

## Security posture

- Server URL + session JWT stored in `EncryptedSharedPreferences`
  (AES-256-GCM via Android Keystore); excluded from cloud backup.
- The session is short-lived (~24h); your Nostr key stays in Amber and
  never touches the app. Google's client secret stays on the server.
- HTTPS only against the public server cert; an optional internal CA
  (legacy enrollment) is trusted *in addition to* the public store.
- Recordings are stored unencrypted in `Music/Vezir/` — treat the phone's
  storage accordingly.

## How it talks to the server

100% Jetpack Compose + OkHttp (no Retrofit). NIP-55 sign-in uses
`nostrsigner:` Android intents (`auth/AmberSigner.kt`), builds a NIP-98
event whose id is computed byte-identically to the server
(`auth/Nip98Event.kt`), and posts it to `/api/auth/nostr/login`. Google uses
the device grant via the server (`auth/GoogleLoginApi.kt`). All sign-in
paths converge on the same `/api/me` team discovery.

See the [Vezir README](https://github.com/pretyflaco/vezir#sign-in--access)
for the end-to-end team setup.

## License

MIT, matching upstream vezir.
