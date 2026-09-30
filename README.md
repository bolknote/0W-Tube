# 0W-Tube

**0W-Tube** ("Zero-Wait Tube") is a lightweight Android TV video search and playback client for RUTUBE, public VK Video, Dzen, OK, and public PeerTube content. It is designed for low-memory TV devices and projectors where the official applications are too heavy or slow.

The application uses a native Android interface, the system on-screen keyboard, direct network requests, and hardware-only video decoding. It supports phones as well as TVs, without a WebView or VK user account. On phones, audio-only playback can continue in a foreground service with a playback notification.

<img width="1000" height="580" alt="" src="https://github.com/user-attachments/assets/7f99ef2e-7f15-49ec-9232-1eb565594293" />

## Features

- Unified, concurrent search across RUTUBE, public VK Video, Dzen, OK, and PeerTube results.
- Local title-relevance ranking with known maximum quality as a tie-breaker.
- Maximum-quality badges are filled in on video cards without blocking the UI.
- Anonymous VK Video web session created automatically; no account or user token is required.
- Selectable quality filters: any quality, 720+, 1080+, 1440+, and 2160/4K.
- Search results and resolved qualities are cached in memory, so changing a quality filter does not repeat backend searches.
- Validation against the formats actually available for each video.
- Highest useful hardware-supported stream selected for playback, up to 3840×2160.
- HLS, DASH, and direct-stream playback through AndroidX Media3/ExoPlayer.
- Hardware-only video decoder selection to avoid expensive software decoding.
- TV remote navigation throughout search results and quality filters.
- Lightweight thumbnail grid with view recycling and a bounded in-memory image cache.
- Playback controls displayed over the video.
- Playback speed from 0.75× to 2× with unchanged pitch, and per-video hardware-accelerated mirroring.
- A movable playback overlay on touchscreens: drag it away from subtitles; it returns on release.
- Local watch history for up to 1000 videos, with thumbnails loaded as cards become visible.
- Traffic-saving quality selection and audio-only playback.
- Restoration of the last search, selected quality filter, open video, and playback position.
- Asynchronous GitHub Release update checks with SHA-256 verification and the Android system installer.
- Per-video local watch progress after the first minute, including subsequent rewinds back to the beginning.
- Fresh signed stream URLs resolved immediately before playback.
- Local crash diagnostics retained without an intrusive launch dialog.

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
- Down: open history; hold Down while navigating the video grid. In history, hold Up to return, or press Up at its top edge.

On phones, swipe left across the grid to open history and right to return. The keyboard's search action submits the query.

### Player

- Center/OK: pause or resume playback.
- Left: seek backward 15 seconds.
- Right: seek forward 30 seconds.
- Hold Left/Right: move the seek bar; release to seek to the selected position.
- Up: show the playback controls.
- Down on Android TV: open history.
- Menu (≡), or hold Center/OK: open speed and mirroring settings.
- Back: close settings or return to the previous search/history screen.

The control overlay hides automatically during playback and shortly after pausing. Touch controls remain available on phones.

## Search and playback behavior

RUTUBE search uses its public video search endpoint. Playback options are resolved immediately before opening a video, and the HLS stream is selected from the returned balancer data.

VK Video search uses the same anonymous web API flow as the public `vkvideo.ru` search page. The application obtains a short-lived anonymous session and never asks for a VK account token. Public playback metadata is resolved separately for each selected video.

Dzen search uses its public JSON search API, with a server-rendered HTML fallback. The application keeps only anonymous session cookies, resolves fresh public stream metadata before playback, and uses the same quality filters as the other sources.

OK search reads the public anonymous video-search component. Playback follows the same public metadata flow as yt-dlp's Odnoklassniki extractor: inline player metadata first, then the metadata endpoint and mobile-page fallback. Native OK HLS, DASH, and direct streams are supported without an account; external providers are ignored unless the application already supports them.

PeerTube search combines the public Sepia Search and PeerSeek indexes as a low-priority backend, merging the original title and its Latin transliteration. Discovery requests go only to those search indexes; no fixed PeerTube-instance list or direct-instance search is used. Video origins are taken from index results and contacted only to verify metadata and resolve playback. Requests use a two-worker bounded pool and a 25-second total budget; stopped origins do not prevent checking the next candidates. Results are conservatively filtered for a complete film title and long-form duration, deduplicated by origin/video identity, then verified against the originating server before a card is shown. There is no application-owned index or persistent search cache. Playback prefers a reachable HLS master playlist; traffic-saving audio mode prefers an explicit audio-only file or HLS audio rendition and uses a low-resolution muxed stream only as a fallback.

Private, deleted, region-restricted, DRM-protected, age-gated, or account-only videos may not be available. These public web interfaces can change independently of the application and may require future compatibility updates.

## Local data and privacy

0W-Tube stores only application state on the device:

- the last search query and selected filter;
- the last open video and position;
- per-video playback progress after one minute;
- up to 1000 history entries and saved progress/mirroring records;
- playback speed, traffic-saving preferences, and mirroring state;
- the most recent crash diagnostic.

No user account credentials or permanent VK access tokens are stored. Search and playback requests are sent directly from the device to the respective video services and their CDN hosts. Android cloud backup and device transfer are disabled for application data, including history and page URLs that may contain access keys. Thumbnails have a bounded RAM cache and are not cached on disk.

## Installation

Signed APKs are published on the GitHub Releases page for every version tag. Each release also includes a SHA-256 checksum file.

To install a locally produced debug build instead, use:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it with ADB:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The package ID remains `ru.tubetv.app`. Upgrades preserve settings and watch progress when signed with the same key. A locally signed debug build cannot replace a release-signed installation without uninstalling it first.

## Building

Requirements:

- JDK 17;
- Android SDK with API 36 installed;
- The included Gradle Wrapper downloads the pinned Gradle 9.5.0 distribution.

Build the debug APK:

```sh
./gradlew :app:assembleDebug
```

Run the regression tests, Android lint, and both release builds:

```sh
./gradlew :app:testDebugUnitTest :app:lintRelease :codecprobe:lintRelease assembleRelease
python3 -m unittest research.test_peertube_search_prototype
```

The current application version is defined in `app/build.gradle`.

Without release-key environment variables, release builds are unsigned. `codecprobe` is a separate diagnostic APK; it is not included in 0W-Tube. The `check.yml` workflow runs verification on main-branch pushes and pull requests.

After playback changes, also exercise these scenarios on a phone and an Android TV device: close the player while its stream is resolving; switch between foreground and background audio; return immediately after sending audio to the background; switch to another video; rewind a watched video below one minute and reopen it; hold/release the seek buttons; change mirroring and playback speed. Local JVM tests do not exercise a device's audio/video drivers.

## Tagged releases

Pushing a Git tag triggers `.github/workflows/release.yml`. The workflow verifies that the tag matches `versionName`, runs tests and Android lint, builds a minified unsigned APK, optimizes its ZIP compression, aligns it, and signs it once with the persistent release key. It verifies alignment and the signature before creating a GitHub Release. The R8 mapping is preserved as a separate workflow artifact for decoding crash reports.

The signing key and passwords are supplied through the `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` repository secrets. Never commit a release keystore or its passwords.

## Project structure

```text
app/src/main/java/ru/tubetv/app/
  MainActivity.java          Search UI, filters, results, and state restoration
  SearchClient.java          RUTUBE search client
  VkWebClient.java           Anonymous VK Video search client
  DzenClient.java            Anonymous Dzen search client
  OkClient.java              Anonymous OK search and player metadata client
  PeerTubeClient.java        Federated PeerTube search, filtering, and stream inspection
  PeerTubeSearch.java        Bounded multi-index discovery
  VideoRanker.java           Fast local title-relevance ranking
  StreamResolver.java        Fresh stream resolution for every supported backend
  MediaCapabilities.java     Cached dimensions, without temporary stream URLs
  BoundedHttpReader.java     Response size limits and cancellation checks
  ParallelTasks.java         Shared bounded quality-inspection pool
  PlaybackEngineFactory.java Shared hardware-decoder and streaming configuration
  ScopedCookies.java         Request-scoped playback cookies
  PlayerActivity.java        Media3 player and TV remote controls
  AudioPlaybackService.java  Foreground audio playback on phones
  WatchHistoryStore.java     Bounded local history
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
