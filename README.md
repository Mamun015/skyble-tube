# Skyble Tube

Skyble Tube is a standalone Android app that downloads YouTube videos. It has its
own launcher icon and user interface, and it is not a plugin library. Files are
saved to the public Downloads folder and are not reported to Skyble Browser.

## Features

- Launcher app with its own UI
- Download, Pending and Done sections with progress bars
- Paste a link or share one from another app
- Two download paths: DownloadManager for single progressive files, and a chunked
  downloader (4 MB byte ranges, link refreshed when refused) that joins separate
  picture and audio tracks with MediaMuxer into one MP4
- Saves to the public Downloads folder

## Install

Download the APK from the [latest release](https://github.com/Mamun015/skyble-tube/releases/latest/download/SkybleTube.apk)
and open it on the phone. Android 5.0 or newer (API 21+).

## Build

There is no Gradle and no Android Studio project. The app builds with the plain
Android SDK tools on the phone itself.

Requirements:

- JDK 17
- `aapt`, `zipalign` and `apksigner` on `PATH`
- `sdk/android.jar` (API 33) and `sdk/r8.jar` in the repository, or in
  `../skyble-browser/sdk`

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 PATH=/usr/lib/jvm/java-17-openjdk-arm64/bin:$PATH ./build.sh
```

`build.sh` packages resources, compiles the sources, dexes with R8, aligns, signs
and verifies the APK. Output: `SkybleTube-1.2.apk` (versionName 1.2, versionCode 9).

Core library desugaring is required because NewPipeExtractor uses Java 8+ APIs:
D8 is given `--desugared-lib`, L8 dexes the j$ runtime from
`libs/desugar_jdk_libs-2.0.4.jar`, and the APK ships `classes.dex` plus
`classes2.dex`.

Java only. Signing uses the key chain read by `sign.sh`: set
`SKYBLE_SIGNING_DIR` (default `$HOME/Keystores/skyble`) or build with
`UNSIGNED=1`. The chain holds the previous key for Android 5–8, the current key
for Android 9+, and the lineage between them.

## How other apps call it

```java
Intent i = new Intent("com.skyble.tube.DOWNLOAD");
i.putExtra("url", "https://www.youtube.com/watch?v=...");
i.setPackage("com.skyble.tube");
startActivity(i);
```

It also answers `ACTION_SEND` (`text/plain`) for shared links and `ACTION_VIEW`
for `youtube.com` and `youtu.be` links.

The old action name `com.skyble.tube.plugin.DOWNLOAD` is still accepted for
compatibility, even though the old package `com.skyble.tube.plugin` is gone.

## License

Licensed GPL-3.0-or-later, because it bundles NewPipeExtractor 0.24.6
(GPL-3.0-or-later, Copyright (C) 2015 Christian Schabesberger and the NewPipe
e.V. contributors). Bundled unmodified: jsoup (MIT), Rhino (MPL-2.0), JSR-305
(Apache-2.0), nanojson (Apache-2.0, TeamNewPipe fork) and desugar_jdk_libs 2.0.4
(GPL-2.0 with Classpath Exception). Copyright line: "Skyble Tube / Copyright (c)
2026 Mamun". License text is in `LICENSE`.
