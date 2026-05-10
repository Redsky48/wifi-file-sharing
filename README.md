# WiFi Share

Tiny Android app that turns your phone into a LAN file-sharing server. Drop a folder, start the server, open the URL on your PC's browser — drag & drop files in/out. No PC app needed.

## Stack

- **Native Android (Kotlin) + Jetpack Compose** — small APK, fast, easy to extend
- **NanoHTTPD** as the embedded HTTP server
- **Vanilla HTML/CSS/JS** for the PC web interface (served from APK assets)
- **Storage Access Framework** — user picks any folder, app gets persistent permission
- **Foreground service** — server keeps running when app is backgrounded

Total APK size: ~3-5 MB.

## Project layout

```
.
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/com/wifishare/
│       │   ├── MainActivity.kt           # Compose entry
│       │   ├── WifiShareApp.kt           # Application + notification channel
│       │   ├── data/Settings.kt          # DataStore preferences
│       │   ├── server/
│       │   │   ├── FileServer.kt         # NanoHTTPD endpoints
│       │   │   └── ServerService.kt      # Foreground service
│       │   ├── ui/
│       │   │   ├── HomeScreen.kt         # Status + start/stop
│       │   │   ├── SettingsScreen.kt     # Folder picker, port, toggles
│       │   │   ├── MainViewModel.kt
│       │   │   └── Theme.kt
│       │   └── util/Network.kt           # Local IP detection
│       ├── assets/web/                   # PC browser UI
│       │   ├── index.html
│       │   ├── style.css
│       │   └── app.js
│       └── res/                          # Strings, theme, icons
├── build.gradle.kts                      # Root
├── settings.gradle.kts
└── app/build.gradle.kts                  # App module
```

## Build

### Prerequisites

- **JDK 17**
- **Android SDK** (API 34) — installable via Android Studio or `sdkmanager`
- Either **Android Studio** or **Gradle 8.9+** on the command line

### Option A — Android Studio (easiest)

1. Open the project folder in Android Studio.
2. Studio downloads Gradle wrapper, SDK components, and dependencies automatically.
3. Plug in your phone (USB debugging on) → click Run.

### Option B — Command line

The project ships without `gradle-wrapper.jar` (binary) — generate it once:

```bash
gradle wrapper --gradle-version 8.9
```

Then build:

```bash
# Debug build (installs as com.wifishare.debug)
./gradlew assembleDebug
./gradlew installDebug

# Release build (smaller, requires signing for install)
./gradlew assembleRelease
```

Output APK: `app/build/outputs/apk/debug/app-debug.apk`

## Usage

1. Open the app, tap **Settings**, pick the folder you want to share.
2. (Optional) Toggle **Allow uploads** / **Allow delete**, change the port.
3. Back on Home, tap **Start**. The card shows a URL like `http://192.168.1.42:8080`.
4. On your PC (same WiFi), open that URL in any browser.
5. The PC browser shows the file list, supports drag & drop upload, and lets you click to download.

The phone keeps serving while the app is backgrounded (foreground service with notification).

## API endpoints

The web UI uses these — handy if you want to script things:

| Method | Path                          | Notes                                |
|--------|-------------------------------|--------------------------------------|
| GET    | `/`                           | Web UI (`assets/web/index.html`)     |
| GET    | `/static/<name>`              | Static assets (CSS/JS)               |
| GET    | `/api/files`                  | JSON file list + permission flags    |
| GET    | `/api/download?name=<n>`      | Download a single file               |
| POST   | `/api/upload`                 | Multipart upload (any field name)    |
| DELETE | `/api/delete?name=<n>`        | Delete a file (if allowed)           |

## Extending

Common next steps and where to touch:

- **Folder navigation / subfolders** → `FileServer.listFiles()` + `app.js` rendering
- **Optional PIN auth** → middleware in `FileServer.serve()` checking a header/cookie + new setting in `Settings.kt`
- **QR code on home screen** → add a QR lib (e.g. `zxing-android-embedded`), render in `HomeScreen.StatusCard`
- **Auto-start on boot** → `BOOT_COMPLETED` receiver, gated by `AppSettings.autoStart`
- **iOS / desktop** → not in scope; keep this repo phone-only and pair a separate web client if needed

## Security note

There is no authentication by default — anyone on the same WiFi network who guesses the URL can read/write the shared folder (within the toggles you've set). Use only on trusted networks, or add the PIN feature above before shipping further.
