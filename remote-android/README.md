# Frame Player Remote for Android

This optional Android companion controls Frame Player on the local network. The current integration has been built on Windows. Android API 26 or newer is required.

## Build and run

1. Build and run the desktop application using the [Frame Player build instructions](../README.md#building).
2. Open `remote-android` as a project in Android Studio. Use Android SDK 35 and JDK 17 or 21.
3. Build the debug APK from Android Studio. The output is under `app/build/outputs/apk/debug/` inside the Android project.

On Windows, the following script can also build and export a signed debug APK. Set `JAVA_HOME` to your JDK or put its `bin` directory on `PATH`. The script downloads the pinned Android build tools on the first run.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File '.\remote-android\Build-DebugApk.ps1'
```

It writes `FramePlayerRemote-<version>-debug.apk` at the repository root. On reruns, use the same Android signing key if you want to install an update over an existing build.

## Pair and use

1. Connect both devices to the same local network.
2. Choose **Enable remote control** in Frame Player’s TV settings.
3. Select the PC in the Android app. Compare the pairing code and approve the phone on the PC.
4. Open a video. Use **Watch on TV** to select a receiver discovered by the desktop player.

The remote supports playback, 10-second jumps, the jog control, volume, audio and subtitle selection, repeat and the play queue. For eligible local videos, the desktop sends a silent preview at up to 15 frames per second. The image follows the jog position and playback seeks when the jog stops.

The remote uses a pinned TLS connection after pairing. The paired phone can control the desktop player on the local network. Turn off the remote or forget the phone in the desktop settings to end access.

See the [project license](../LICENSE) and [third-party notices](../THIRD-PARTY-NOTICES.md).
