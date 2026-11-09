# Live Screen Mirror • Android

A lightweight Android screen mirroring app powered by the system **MediaProjection** API. No Firebase, cloud backend, account or screen recordings.

## Features

- **Wi-Fi share:** display your phone screen in a browser on another phone or computer connected to the **same Wi-Fi**. URL has a random 128-bit session token and expires as soon as you stop sharing.
- **Floating window:** secure draggable preview over other apps, with a stop control.
- System permission for **every** capture session, foreground notification with a stop action.
- No image saving or third-party SDKs. The browser is read-only (does not control the phone).
- Android 10+ (API 29), built with Kotlin, AGP 8.7.3 and Gradle 8.9.

## APK without a computer

1. Open this repo's **Actions** tab, choose **Build APK** and click **Run workflow** (or use the automatic build after commits).
2. Open the successful run and download **LiveScreenMirror-debug-APK** from **Artifacts**. GitHub provides a ZIP containing `app-debug.apk`.
3. Extract the ZIP on your Android phone, tap the APK and allow installation from that source if Android asks.
4. Open the app and select Wi-Fi sharing or floating-window mode. Grant the Android screen-sharing consent.

## Wi-Fi sharing

Both devices must use the same local Wi-Fi network, with client-to-client traffic enabled. Copy the address shown inside the app and open it in the other device's web browser. Sharing works without an internet relay and never starts automatically. Sharing uses **unencrypted HTTP** on the local network; avoid untrusted public Wi-Fi, never share the URL publicly, and stop the session when finished. Mobile network remote access and audio are **not** implemented.

## Build in Android Studio

Install JDK 17 and Android SDK Platform 35. Open the repository and run the `app` configuration. Or run `gradle :app:assembleDebug` with Gradle 8.9.

## Limits

- The Wi-Fi server needs an active Android Wi-Fi network and a reachable IPv4 address; some guest networks block devices from reaching each other.
- Secure windows (e.g., protected banking/DRM content) may display black because Android forbids capture.
- Rotation/layout changes use `VirtualDisplay.resize` where supported; if a device behaves oddly, restart screen sharing.
- The app has no remote control, no audio, no public internet relay, and no encryption. It's intentionally a local, consent-based screen viewer.

## Android API compliance

Android 14+ requires a new user consent for each capture session and a `MediaProjection.Callback` before creating the `VirtualDisplay`. This app uses one display per session and starts a correctly typed foreground service before calling `getMediaProjection`.
