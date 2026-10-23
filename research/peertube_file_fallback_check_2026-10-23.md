# PeerTube missing-file fallback

Test date: 2026-10-23

## Cause and change

The public audio file for “Андрей Рублёв” on `tube.azbyka.ru`
(`74a3053c-40ac-4d03-9c2a-3a813db6428d`) is not deleted. Its API `fileUrl`
returns nginx HTTP 404, but the adjacent `fileDownloadUrl` serves the same valid
AAC/MP4 audio with HTTP 206 and byte-range support. `ffprobe` reports AAC LC,
48 kHz mono, about 70 kbit/s, duration 03:26:00.83. The exact server-side static
routing/configuration fault cannot be determined without server access.

The resolver now retains the API-provided download alternate for the selected
file. A small Media3 DataSource wrapper retries it only when opening that exact
primary URI returns 404 or 410. It closes the failed request first and preserves
the complete DataSpec, including byte position/length and headers. A successful
alternate is shared across this player's data-source instances, so later seeks
do not retry the dead static URL. No decoder/player recreation is needed.

There is no preflight request, URL-path guessing, fixed-instance workaround,
server-side transcoding, or full-file download. No fallback occurs for other
HTTP/network errors, unrelated HLS segments, or absent/invalid alternates. A
failed alternate is not retried recursively. Background audio receives the same
alternate in its existing player handoff and uses the same network factory.

## Verification

- 74 Java unit tests passed, including selected-file URL pairing, HTTPS-only
  alternates, no added resolver probes, and no master-playlist replacement.
- 14 Python prototype tests passed.
- Release lint, debug, minified unsigned release, and Android-test builds passed.
- The Android test adds no library dependency and is not included in the app APK.
- Application version remains 0.7.4 / versionCode 32; no tag or release was made.

On a read-only Android 14 / API 34 ARM64 emulator, deterministic tests exercised
the actual Media3 wrapper with injected responses:

- 404 and 410: one retry, failed request closed, full DataSpec retained;
- 400/401/403/405/408/416/429/500/503 and a generic network exception: no fallback;
- an unrelated URI: no replacement;
- failed alternate: no recursive loop or successful-target caching;
- a new data-source instance after seeking: skips the dead primary.

## Live checks through the production Android network factory

Each audio stream was read in 4096-byte ranges at offsets 0, 1048576, and 2097152.
The test checked exact Content-Range values, complete reads, and request counts.

| Video | Origin | Audio requests | Normal playback source |
| --- | --- | --- | --- |
| Андрей Рублёв | `tube.azbyka.ru` | One 404 + three successful download-URL ranges | HLS master readable |
| Кин-дза-дза | `peertube.terranout.mine.nu` | Three primary-URL ranges; no alternate request | HLS master readable |
| Солярис | `pablo.tube` | Three primary-URL ranges; no alternate request | HLS master readable |
| Кин-дза-дза | `pablo.tube` | Three primary-URL ranges; no alternate request | HLS master readable |

The final run passed every case without a network retry or skipped origin.
Earlier runs encountered intermittent object-storage timeouts. The live harness
compares such a failure against the original unwrapped transport and permits one
explicit, bounded test retry when the server recovers. This does not change the
application's timeout/retry policy. It also accepts the resolver's existing direct
muxed-file choice if an HLS probe times out, instead of assuming HLS is always selected.

For the problematic Rublev file, the test also created the production ExoPlayer,
sought to 01:00:00, and confirmed advancing playback and rendered audio-decoder
output buffers. This tests more than endpoint availability. The emulator had no
host audio output; physical phone/TV driver behavior and the complete foreground/
background service lifecycle were not separately exercised in this check.

## Reproduce

On a dedicated test emulator (select its serial with `adb -s` if needed):

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintRelease :app:assembleRelease
python3 -m unittest research.test_peertube_search_prototype
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w ru.tubetv.app.test/ru.tubetv.app.PeerTubeFallbackInstrumentation
adb shell am instrument -w -e live true ru.tubetv.app.test/ru.tubetv.app.PeerTubeFallbackInstrumentation
```

The first instrumentation command is offline/deterministic; live checks are
explicitly opt-in. Inspect the runner's `PASS`/`FAIL` output, not just adb's exit
status. Public source availability may change independently of this code.
