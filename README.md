# 0W-Tube

**0W-Tube** ("Zero-Wait Tube") is a lightweight Android TV video search and playback client for RUTUBE and public VK Video content. It is designed for low-memory TV devices and projectors where the official applications are too heavy or slow.

The application uses a native Android interface, the system on-screen keyboard, direct network requests, and hardware-only video decoding. It does not embed a WebView, require a VK user token, or run a background service.

## Features

- Unified search across RUTUBE and public VK Video results.
- Anonymous VK Video web session created automatically; no account or user token is required.
- Selectable quality filters: any quality, 720+, 1080+, 1440+, and 2160/4K.
- Validation against the formats actually available for each video.
- Highest useful hardware-supported stream selected for playback, up to 3840×2160.
- HLS playback through AndroidX Media3/ExoPlayer.
- Hardware-only video decoder selection to avoid expensive software decoding.
- TV remote navigation throughout search results and quality filters.
- Lightweight thumbnail grid with view recycling and a bounded in-memory image cache.
- Playback controls displayed over the video.
- Restoration of the last search, selected quality filter, open video, and playback position.
- Per-video local watch progress after the first minute, displayed on result cards.
- Fresh signed stream URLs resolved immediately before playback.
- Crash diagnostics shown on the next application launch.

## Target device

0W-Tube was created for and tested on an XGIMI HORIZON Pro with:

- Android 11 / API 30;
- four ARM Cortex-A55 cores at up to 1.5 GHz;
- approximately 1.7 GB of usable RAM and a 192 MB application heap;
- 32-bit `armeabi-v7a` userspace;
- Mali-G52 graphics;
- a 1920×1080 Android UI surface and 3840×2160 projector output;
- hardware AVC/H.264, HEVC/H.265, VP8, VP9, AV1, and AAC decoders.

The minimum supported Android version is Android 6.0 (API 23). Other Android TV devices should work, but have not been tested.

## Remote controls

### Search screen

- D-pad: move between the search field, quality filters, and video cards.
- Center/OK: select a filter, start a search, or open a video.
- System keyboard: enter the search query.

### Player

- Center/OK: pause or resume playback.
- Left: seek backward 15 seconds.
- Right: seek forward 30 seconds.
- Up/Down: show the playback controls.
- Back: return to the search results.

The control overlay hides automatically while the video is playing.

## Search and playback behavior

RUTUBE search uses its public video search endpoint. Playback options are resolved immediately before opening a video, and the HLS stream is selected from the returned balancer data.

VK Video search uses the same anonymous web API flow as the public `vkvideo.ru` search page. The application obtains a short-lived anonymous session and never asks for a VK account token. Public playback metadata is resolved separately for each selected video.

Private, deleted, region-restricted, DRM-protected, age-gated, or account-only videos may not be available. These public web interfaces can change independently of the application and may require future compatibility updates.

## Local data and privacy

0W-Tube stores only application state on the device:

- the last search query and selected filter;
- the last open video and position;
- per-video playback progress after one minute;
- the most recent crash diagnostic.

No user account credentials or permanent VK access tokens are stored. Search and playback requests are sent directly from the device to the respective video services and their CDN hosts.

## Installation

Build the debug APK or use an APK produced from the current source tree:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it with ADB:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The package ID remains `ru.tubetv.app`, allowing upgrades to preserve local settings and watch progress.

## Building

Requirements:

- JDK 17;
- Android SDK with API 36 installed;
- Gradle 8.14 or a compatible version.

Build the debug APK:

```sh
gradle :app:assembleDebug
```

Run Android lint:

```sh
gradle :app:lintDebug
```

The current application version is defined in `app/build.gradle`.

## Project structure

```text
app/src/main/java/ru/tubetv/app/
  MainActivity.java          Search UI, filters, results, and state restoration
  SearchClient.java          RUTUBE search client
  VkWebClient.java           Anonymous VK Video search client
  StreamResolver.java        Fresh RUTUBE and VK Video stream resolution
  PlayerActivity.java        Media3 player and TV remote controls
  WatchProgressStore.java    Per-video local playback progress
  ImageLoader.java           Bounded thumbnail loader and memory cache
```

The `research` directory contains notes used to understand device capabilities and public playback formats. Downloaded APKs and decompiled source trees are intentionally excluded from Git.

## Acknowledgements

- [AndroidX Media3](https://developer.android.com/media/media3) for playback.
- [yt-dlp](https://github.com/yt-dlp/yt-dlp) as a reference for public video format behavior.

## License

Copyright © 2026 Evgeny Stepanischev.

This project is licensed under the [MIT License](LICENSE).
