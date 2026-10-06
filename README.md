# Omniverse

A media player for Android TV, Google TV, Fire TV, Onn and NVIDIA Shield. (Phone and tablet layouts are coming later.)

**Omniverse does not provide, host or include any content, channels or playlists.**
You sign in with a source you already have (an Xtream login or an M3U playlist link),
and it only plays what that source gives you. Use it only with sources you are entitled to watch.

## Install

**Always-latest download link:**

```
https://github.com/omniversepackage/omniverse/releases/latest/download/omniverse.apk
```

### Fire TV / Firestick / Onn / Google TV (using the Downloader app)
1. Install **Downloader** (by AFTVnews) from your TV's app store.
2. Open Downloader and type the link above into the URL box, then press **Go**.
3. When it finishes, choose **Install**. If asked, allow Downloader to install unknown apps.
4. Open **Omniverse** and sign in with your source.

## Updates
Omniverse checks for new versions by itself and offers to install them. Nothing to do.

## Releases
Every version is listed under [Releases](../../releases), with its SHA-256 checksum.

## Source code

The app source is published in this repository for transparency. The Android TV app and
shared Kotlin modules are under `android/` and `core/`; Gradle build logic and wrapper
files are included. Local signing keys and machine-specific configuration are not.

To run the test suite and build a debug APK, install JDK 17 and the Android SDK, set
`ANDROID_HOME` to that SDK, then run:

```sh
./gradlew test :android:app:assembleDebug
```

This source snapshot does not include a separate license grant. The app's third-party
notices are in [NOTICE.md](NOTICE.md).
