<p align="left">
  <picture>
    <source media="(prefers-color-scheme: dark)"
            srcset="https://raw.githubusercontent.com/pretyflaco/vezir/main/assets/logo/vezir-logo-light.svg">
    <img src="https://raw.githubusercontent.com/pretyflaco/vezir/main/assets/logo/vezir-logo.svg"
         alt="vezir" width="320">
  </picture>
</p>

# vezir-android

[![GitHub release downloads](https://img.shields.io/github/downloads/pretyflaco/vezir-android/total.svg)](https://github.com/pretyflaco/vezir-android/releases)
[![Latest release](https://img.shields.io/github/v/release/pretyflaco/vezir-android.svg)](https://github.com/pretyflaco/vezir-android/releases/latest)

Android client for [Vezir](https://github.com/pretyflaco/vezir) —
self-hosted team intelligence. Record a meeting on the phone (system audio
+ microphone), encode to OGG/Opus on-device, and upload to your Vezir
server over plain HTTPS. The server transcribes, diarizes, summarizes,
labels speakers, and syncs to your team archive. Sign in with **Nostr** or
**Google** — no VPN, no token pasting.

## Status

Alpha (**0.14.0**). Sideload only; no Play Store. Full history in
[`CHANGELOG.md`](CHANGELOG.md).

## Sign in

On first launch, point the app at your server (e.g.
`https://vezir.twentyone.ist`) and pick one:

- **Sign in with Nostr** — uses any local NIP-55 signer app
  ([Amber](https://github.com/greenart7c3/Amber/releases), Blink, …)
  holding your key. The app shows the system signer chooser; approve
  `get_public_key` then the login signature in your signer. Your key
  never touches the app.
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
| Sign in | Nostr (any NIP-55 signer) or Google, per above. |
| Record meeting | **Start recording** → Android `MediaProjection` consent → captures system playback + mic, mixes with soft-clip, encodes Opus. 3h hard cap. |
| Save | Audio OGG lands in `Music/Vezir/vezir-<timestamp>.ogg`; screen recordings land in `Movies/Vezir/` as MP4. |
| Record screen | **Record screen + mic (MP4)** → `MediaProjection` consent → H.264 screen capture muxed with mic audio, saved to `Movies/Vezir/`. Uploads as video; the server samples cue frames from it. |
| Summary template | Sticky **iteration plan** toggle for a narrated walkthrough of a build — the server summarizes it from the screen, not just the narration. Needs server ≥ 0.18.0. |
| Attestation | Session detail states the backend and model that produced the summary (e.g. `tinfoil/glm-5-3-flash (hardware-attested TEE)`); the list badges only the exception, `unattested`. Needs server ≥ 0.20.0. |
| Auto-label / Sync / Personal | Switches on the record screen (sticky, except Personal which resets per launch), mirroring the desktop toggles. |
| Upload | Resumable multipart upload with progress; polls `/api/sessions/{id}` to completion. |
| Browse | Sessions tab: status, transcripts, summaries, artifacts. |
| Import existing recording | SAF picker. Audio is decoded → resampled → Opus; an **MP4/MOV is copied through untouched** (transcoding it discarded the video, and the video is the point). Voice memos and prior Vezir OGGs work too. |

## Requirements

- Android 10 (API 29)+.
- A reachable Vezir server. **≥ 0.20.0** for everything in this README
  (attestation display); ≥ 0.18.0 for video upload and the iteration-plan
  template; ≥ 0.8.0 is the bare floor for Nostr/Google sign-in.
- Your identity authorized on the server (`npub` or `@domain` email) and a
  team membership — ask your operator.
- For Nostr sign-in: a NIP-55 signer app
  ([Amber](https://github.com/greenart7c3/Amber/releases), Blink, …) on
  the phone holding your key.
- **No VPN.** The server is reached over ordinary HTTPS.

## Install

The signed APK is attached to each [GitHub Release](https://github.com/pretyflaco/vezir-android/releases/latest).

```bash
adb install -r vezir-android-0.14.0.apk
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

The release config reads `keystore.properties` (gitignored) from, in
order:

1. `$VEZIR_ANDROID_KEYSTORE_PROPS` (explicit override),
2. `~/.android-keystores/vezir/keystore.properties` (canonical — outside
   the working tree since 0.8.0, so it can't be committed by accident),
3. `<repo>/keystore.properties` (legacy fallback; discouraged).

```properties
storeFile=/absolute/path/to/vezir-release.jks
storePassword=...
keyAlias=vezir
keyPassword=...
```

Without it, `assembleRelease` falls back to the debug keystore so CI/clones
still build. Generate a keystore once with `keytool -genkey -v -keystore
vezir-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias vezir`.
Each release ships the APK plus a `.sha256` sidecar on the GitHub Release;
the in-app update check picks up new releases automatically.

## Security posture

- Server URL + session JWT stored in `EncryptedSharedPreferences`
  (AES-256-GCM via Android Keystore); excluded from cloud backup.
- The session is short-lived (~24h); your Nostr key stays in your
  signer app and never touches the app. Google's client secret stays on
  the server.
- HTTPS only against the public server cert; an optional internal CA
  (legacy enrollment) is trusted *in addition to* the public store.
- Recordings are stored unencrypted in `Music/Vezir/` (audio) and
  `Movies/Vezir/` (screen recordings) — treat the phone's storage
  accordingly. A screen recording is the more sensitive of the two: it
  captures whatever was on screen, including anything you did not intend
  to demo.

## How it talks to the server

100% Jetpack Compose + OkHttp (no Retrofit). NIP-55 sign-in uses
`nostrsigner:` Android intents (`auth/Nip55Signer.kt`), builds a NIP-98
event whose id is computed byte-identically to the server
(`auth/Nip98Event.kt`), and posts it to `/api/auth/nostr/login`. Google uses
the device grant via the server (`auth/GoogleLoginApi.kt`). All sign-in
paths converge on the same `/api/me` team discovery.

See the [Vezir README](https://github.com/pretyflaco/vezir#sign-in--access)
for the end-to-end team setup.

## License

MIT, matching upstream vezir.
