# vezir-android

Android thin client for [Vezir](https://github.com/pretyflaco/vezir).

Records meeting audio on-device, encodes it to OGG/Opus, and uploads to a
self-hosted vezir server, where the existing meetscribe pipeline handles
transcription, diarization, summarization, and PDF generation.

This is the v1 milestone scaffold — see `MILESTONES.md` for the per-step
plan. M1 ships:

- Setup screen (server URL + token, encrypted at rest).
- `/health` reachability probe.
- `/api/sessions` token validity probe.
- Manual JSON-paste enrollment compatible with the server's `/admin/enroll`
  QR payload.

Capture (MediaProjection + mic, OGG/Opus on-device, 3h hard cap), upload,
status polling, SAF import, and camera-based QR scan land in M2–M5.

## Requirements

- Android 10 (API 29) or newer on the device.
- A reachable vezir server (`vezir serve`) and a token issued via
  `vezir token issue --github <handle>` or, preferably,
  `vezir token enroll --github <handle>`.
- For Tailscale-only HTTP servers, the vezir host must be added to
  `app/src/main/res/xml/network_security_config.xml` before building.

## Build

This repo does not check in the Gradle wrapper jar. Bootstrap once with
a system Gradle 8.x:

```bash
gradle wrapper --gradle-version 8.10 --distribution-type bin
./gradlew assembleDebug
```

The unsigned debug APK is written to
`app/build/outputs/apk/debug/app-debug.apk`.

## Test

JVM-only unit tests run without an emulator:

```bash
./gradlew test
```

## Onboarding flow (operator + scribe)

1. Operator runs on the vezir server:

   ```bash
   vezir token enroll --github <handle> --server https://muscle.tail178bd.ts.net:8000
   ```

2. Operator opens the printed `/admin/enroll` URL in an authenticated
   browser tab and pastes the URL + token into the form. The page
   renders a QR code.

3. Scribe opens Vezir on Android:
   - Tap **Apply pasted JSON** and paste the JSON shown in the
     `/admin/enroll` page's "Manual entry / payload" details, OR
   - In M5 onward, tap **Scan QR** and point the camera at the page.

4. Tap **Test connection** then **Verify token**, then
   **Save and continue**.

## Security posture

- Server URL and token are stored in `EncryptedSharedPreferences`
  (AES-256-GCM via Android Keystore). The pref file
  `vezir_secure_prefs.xml` is excluded from cloud backup and device
  transfer.
- HTTPS by default. Cleartext HTTP is allowed only for hosts explicitly
  listed in `network_security_config.xml` at build time. The shipped
  config has no preset cleartext hosts; you must add yours and rebuild.
- v1 is intended for sideload distribution from GitHub Releases. It is
  not a Play Store target.

## License

MIT, matching upstream vezir.
