# Clipcat Android

Take and send photos from Android directly to Clipcat on Windows over your local network.

## Table of Contents
- [Features](#features)
- [Basic Usage](#basic-usage)
- [Installation](#installation)
- [Uninstall](#uninstall)
- [Troubleshooting](#troubleshooting)
- [Privacy and Security](#privacy-and-security)
- [Works With](#works-with)
- [Developer Setup](#developer-setup)
- [Releases](#releases)
- [Protocol Notes](#protocol-notes)

## Features
- Full-screen CameraX preview with quick capture flow
- Pairing via QR scan (ML Kit)
- AES-GCM encrypted transfer over local TCP
- Original quality and fast-transfer options
- Quick Settings tile support

## Basic Usage
1. Start Clipcat on Windows and open the pairing QR.
2. On Android, open Clipcat and scan the QR (Pair button).
3. Capture and send.

## Installation

### Option 1: Google Play
- Play Store listing: Coming soon

### Option 2: Manual APK install
1. Download the latest Android release artifact from:

   https://github.com/clipcat-app/clipcat-android/releases
2. On Android, allow install from the source app/browser if prompted.
3. Install the APK and open Clipcat.

## Uninstall

### Standard uninstall
1. Android Settings -> Apps -> Clipcat -> Uninstall.

### Full cleanup (settings + pairing)
1. Android Settings -> Apps -> Clipcat -> Storage & cache.
2. Tap Clear storage / Clear data.
3. Uninstall the app.
4. Remove Clipcat from Quick Settings tiles if still present.

## Troubleshooting
- Pairing fails: Verify both devices are on the same local network and re-scan the QR code.
- Transfer fails: Confirm the Windows app is running and listening.
- Slow transfer: Enable fast transfer mode in app settings.
- Camera permission issues: Re-enable camera permission in Android Settings.

## Privacy and Security
- Images are sent only over your local network.
- Payloads are encrypted with AES-GCM.
- Pairing data is stored using Android encrypted storage.

## Works With
- Windows receiver:

  https://github.com/clipcat-app/clipcat-windows

Compatibility note:
- Keep Android and Windows apps on recent releases to avoid protocol mismatch over time.

## Developer Setup
1. Open this folder in Android Studio and sync Gradle.
2. Build debug version:

```powershell
.\gradlew.bat assembleDebug
```

3. Install debug version:

```powershell
.\gradlew.bat installDebug
```

## Releases

### GitHub Actions
Releases are built on tags like `v1.0.0` (and can also be run manually).

Workflow file:
- [.github/workflows/android-release.yml](.github/workflows/android-release.yml)

The workflow builds signed release artifacts:
- `app/build/outputs/apk/release/*.apk`
- `app/build/outputs/bundle/release/*.aab`

## Protocol Notes
- Pairing payload fields: ip, port, key
- Transport frame: 4-byte payload size + 12-byte nonce + encrypted payload
- Encryption: AES-GCM

## Permissions
- CAMERA
- INTERNET
- ACCESS_NETWORK_STATE
