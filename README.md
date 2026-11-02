# LyricMakerPro

**LyricMakerPro** is an Android lyric-video editor for importing audio, synchronizing lyrics, arranging visual media, and exporting lyric videos.

> Android application developed by Wins Mediatama.

## Project identity

| Setting | Value |
|---|---|
| Application name | LyricMakerPro |
| Android application ID | `com.winsmediatama.lyricmakerpro` |
| Gradle namespace | `com.winsmediatama.lyricmakerpro` |
| Android module | `:app` |
| Minimum Android SDK | 24 (Android 7.0) |
| Compile / Target SDK | 36 / 36 |

The application ID is the package name that must be registered in Firebase and used for Android distribution. Keep it unchanged after publishing; changing it creates a different Android application.

## Features

- Import audio files from the device.
- Import lyric text and synchronize lyrics with audio playback.
- Edit and arrange lyric content on the timeline.
- Add image and video media to the project timeline.
- Preview the composition and export a lyric video.
- Save and reopen project files.

Feature availability and behavior may evolve as development continues.

## Tech stack

- Kotlin
- Jetpack Compose and Material 3
- AndroidX Media3
- Room
- Kotlin Coroutines
- Gradle Kotlin DSL
- Firebase Google Services Gradle plugin and Firebase dependencies

## Build

### Requirements

- Android Studio with a compatible Android SDK.
- JDK 21 for the repository GitHub Actions workflow.
- Android SDK Platform 36 and Build Tools 36.0.0.

### Build locally

On a machine with the Android SDK and Gradle configured:

```bash
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
```

The debug APK is generated at:

```
app/build/outputs/apk/debug/app-debug.apk
```

To build a release bundle locally, configure the release signing environment expected by `app/build.gradle.kts` and run:

```bash
gradle :app:bundleRelease
```

Do not commit signing keys, passwords, or generated credentials.

## Firebase configuration for GitHub Actions

The repository workflow reads the Firebase Android configuration from a GitHub Actions repository secret. The JSON file is generated on the CI runner and is not committed to the repository.

1. Open the [Firebase Console](https://console.firebase.google.com/).
2. Create or select the Firebase project for LyricMakerPro.
3. Register an Android app using this exact package name:

   ```
   com.winsmediatama.lyricmakerpro
   ```

4. Download the generated `google-services.json`.
5. Encode the file contents as Base64.
6. In GitHub, open **Settings → Secrets and variables → Actions → New repository secret**.
7. Create this secret:

   ```
   GOOGLE_SERVICES_JSON_BASE64
   ```

### Encode on Windows PowerShell

Run this command in the directory containing `google-services.json`:

```powershell
[Convert]::ToBase64String(
  [IO.File]::ReadAllBytes(".\\google-services.json")
)
```

Copy the command output into the GitHub Actions secret value. Do not add quotes, line breaks, or the JSON file itself to the repository.

### Encode on Linux or macOS

```bash
base64 -w 0 google-services.json
```

On macOS, use:

```bash
base64 < google-services.json | tr -d '\\n'
```

The workflow decodes the secret to `app/google-services.json` and validates it before running the Android build. If the secret is absent, the workflow can continue without Firebase configuration; Firebase-dependent runtime features may therefore be unavailable. Configure the secret before relying on Firebase services in CI builds.

The file `app/google-services.json` is excluded by `.gitignore`. Never commit service account private keys or other server credentials. The Firebase Android configuration file is a client configuration, not a substitute for securing backend services with Firebase Security Rules and App Check.

## GitHub Actions

The workflow is located at:

```
.github/workflows/android-release.yml
```

It runs for pushes to `main`, pull requests targeting `main`, version tags matching `v*`, and manual workflow dispatches.

- Pull requests and regular branch builds run unit tests and produce a debug APK.
- Release-triggered runs build a signed Android App Bundle (AAB) and APK.
- Release signing uses GitHub Actions secrets:
  - `ANDROID_KEYSTORE_BASE64`
  - `ANDROID_KEYSTORE_PASSWORD`
  - `ANDROID_KEY_PASSWORD`

Keep the upload keystore and its passwords private. Back up the original upload keystore securely.

## Repository layout

```
.
├── app/
│   └── src/
│       ├── main/
│       │   ├── java/com/winsmediatama/lyricmakerpro/
│       │   └── res/
│       ├── test/
│       └── androidTest/
├── .github/workflows/android-release.yml
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/
```

## Development notes

- Keep `applicationId`, `namespace`, and Kotlin package declarations aligned with the project identity.
- Register the exact `applicationId` in Firebase.
- Do not commit `google-services.json`, signing keys, passwords, API secrets, or local `.env` files.
- Run unit tests before merging changes to `main`.
- Increment Android `versionCode` for every Play Store release.

## License

No license has been specified in this repository yet. All rights remain with their respective owners unless a license is added.
