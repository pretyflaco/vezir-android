<p align="left">
  <picture>
    <source media="(prefers-color-scheme: dark)"
            srcset="https://raw.githubusercontent.com/pretyflaco/vezir/main/assets/logo/vezir-logo-light.svg">
    <img src="https://raw.githubusercontent.com/pretyflaco/vezir/main/assets/logo/vezir-logo.svg"
         alt="vezir" width="320">
  </picture>
</p>

# vezir-android

Android thin client for [Vezir](https://github.com/pretyflaco/vezir).

Records meeting audio on the phone (system playback + microphone),
encodes it to OGG/Opus on-device, and uploads to a self-hosted Vezir
server. The existing meetscribe pipeline on the server handles
transcription, diarization, summarization, PDF generation, and sync to
your meetings repo.

## Status

Alpha (0.1.0). Sideload only; no Play Store. End-to-end validated
against a Blink dev-sync sandbox session: phone records a Google Meet
meeting via Android `MediaProjection` + microphone, encodes to OGG/Opus
at 16 kHz mono / 24 kbps, uploads to a Vezir server over Tailscale, and
the server's worker produces a usable transcript + summary.

## What it does

| Action | How |
|---|---|
| Enroll device | Scan the QR rendered by the server's `/admin/enroll` page, or paste the JSON payload manually. |
| Record meeting audio | Tap **Start recording**. Android shows the `MediaProjection` consent prompt. The app captures system playback (apps using `USAGE_MEDIA`/`USAGE_GAME`/`USAGE_UNKNOWN`) + microphone, mixes them with soft-clip, and encodes Opus. |
| Stop | Tap **Stop**, or use the persistent notification's Stop action. 3h hard cap. |
| Save | OGG lands in `Music/Vezir/vezir-<timestamp>.ogg` — visible in every file manager and audio app. |
| Upload | Tap **Upload to vezir**. OkHttp multipart `POST /upload` with progress, retries on connection / 5xx, restart-from-byte-0 on retry. Polls `/api/sessions/{id}` until terminal. |
| Open dashboard | One-tap to the existing browser dashboard via `/login?token=...&next=/s/<id>`. |
| Import existing recording | SAF picker → `MediaExtractor` → `MediaCodec` decode → resample → Opus. Samsung screen-recording MP4s, voice memos, prior Vezir OGGs all work. OGG inputs are stream-copied without re-encode. |

## What it does not do (yet)

- Native labelling UI; the server's web UI is used for that. Open the dashboard from the app.
- Resumable / chunked upload. Current retries restart from byte 0.
- Capture from apps that route audio through Android's communication channel (typically Signal calls, sometimes Zoom). The OS does not expose those streams to third-party recorders. The app detects 10 s of silent playback and surfaces a hint that we have likely fallen back to mic-only.

## Requirements

- Android 10 (API 29) or newer.
- A reachable Vezir server running ≥ 0.1.2 with the `/admin/enroll` endpoint.
- A token issued by the operator: `vezir token issue --github <handle>`.
- For Tailscale HTTP servers, the host must be allow-listed in
  `app/src/main/res/xml/network_security_config.xml` before building.
  Defaults: `muscle.tail178bd.ts.net` and the matching Tailscale IP.

## Install

The signed APK is published with each GitHub Release. Sideload it.

```bash
adb install -r vezir-android-0.1.0.apk
```

Or open the APK in your phone's file manager and let Android install it from "unknown sources".

## Build

The Gradle wrapper jar is checked in, so:

```bash
./gradlew assembleDebug         # debug APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease       # signed release APK (see Signing below)
./gradlew test                  # JVM unit tests (no emulator)
```

Build host requirements: JDK 17, Android SDK with `platforms/android-35`
and `build-tools/35.0.0` installed.

### Signing the release build

The release config reads keystore parameters from `keystore.properties`
(gitignored). Create one alongside `build.gradle.kts`:

```properties
storeFile=/absolute/path/to/vezir-release.jks
storePassword=...
keyAlias=vezir
keyPassword=...
```

Generate a fresh keystore once:

```bash
keytool -genkey -v -keystore vezir-release.jks \
  -keyalg RSA -keysize 4096 -validity 10000 -alias vezir
```

Without `keystore.properties`, `assembleRelease` falls back to the debug keystore so CI builds still succeed.

## Onboarding flow

On the server (operator):

```bash
vezir token issue --github <handle>
```

Open `http://<server>:8000/admin/enroll` in a browser already signed
in to Vezir. Paste the URL + token, hit **Generate QR**.

On the phone:

1. Open Vezir.
2. **Scan enrollment QR**, point at the screen.
3. **Save and continue**.

Manual paste of either the JSON payload (`{"v":1,"url":"...","token":"..."}`)
or the URL + token directly works as a fallback when no camera is
available.

## Security posture

- Server URL and token are stored in `EncryptedSharedPreferences`
  (AES-256-GCM via Android Keystore). The pref file
  `vezir_secure_prefs.xml` is excluded from cloud backup and device
  transfer.
- HTTPS by default. Cleartext HTTP is allowed only for the hosts listed
  in `network_security_config.xml` at build time.
- The recording itself is stored unencrypted in `Music/Vezir/`. Treat
  the phone's storage with the same trust posture you treat your laptop's
  `~/meet-recordings/`.
- Tokens issued by the server are bearer tokens. Lose your phone, run
  `vezir token revoke --github <handle>` on the server.

## License

MIT, matching upstream vezir.
