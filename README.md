# ctrl

Android app that runs an MCP (Model Context Protocol) HTTP server on your phone. An MCP client on your computer can then:

- capture screenshots (via MediaProjection)
- inspect UI structure (via Accessibility)
- send taps/swipes/keys/text (via Accessibility gestures)

This project is meant for **GitHub + sideloading** (not Play Store).

## Security model (read before use)

This app can control your device. Treat it like remote administration software, because that is effectively what it is.

- The server binds to `0.0.0.0` (listens on all interfaces).
- An **IP allowlist** is enabled by default. Requests from non-localhost IPs are rejected unless you explicitly allow them.
- If you disable the allowlist or add broad ranges, any device on your network may be able to send control requests.

Recommended:

- Keep the allowlist enabled.
- Only allow a single trusted machine IP (or a narrow CIDR).
- Use on a trusted LAN only.

## Requirements

- Android device (minSdk 24)
- JDK 17
- Android SDK (Android Studio or command-line tools)

Note: Android Gradle Plugin 8.x runs Gradle on Java 17. If you have multiple JDKs installed, set `JAVA_HOME` to a Java 17 install before running Gradle.

## Clone

This repo includes `kotlin-sdk` as a git submodule.

```bash
git clone --recurse-submodules <your-repo-url>
```

If you already cloned:

```bash
git submodule update --init --recursive
```

## Build APK

From repo root:

```bash
./gradlew :app:assembleDebug
```

APK output:

- `app/build/outputs/apk/debug/app-debug.apk`

## Install (sideload)

With `adb`:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## On-device setup

Open the app and grant the permissions you want to use:

- Notifications (needed for foreground service notifications on Android 13+)
- Screen capture (MediaProjection prompt)
- Accessibility service: "CTRL Accessibility Service"
- Optional: "Draw over other apps" (only needed for overlay dot visualization)

## Using with an MCP client

When the server is running, the app shows an endpoint like:

- `http://<phone-ip>:8787/mcp`

If you enabled the allowlist, add your computer's IP (or a narrow CIDR) in the app.

## Permissions rationale

- `INTERNET`: serve the MCP endpoint.
- `FOREGROUND_SERVICE` + notification permission: required for long-running services.
- MediaProjection foreground service permissions: required for screen capture.
- `BIND_ACCESSIBILITY_SERVICE`: required for UI inspection and input automation.
- `SYSTEM_ALERT_WINDOW`: only for the optional overlay dot feature.
- `QUERY_ALL_PACKAGES`: used for listing installed/launchable apps.

## Not Play Store ready

This app asks for permissions/capabilities (Accessibility, overlays, app enumeration, remote control) that app stores often restrict.

## License

Apache-2.0. See `LICENSE`.
