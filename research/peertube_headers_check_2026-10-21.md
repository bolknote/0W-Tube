# PeerTube media-header compatibility check

Test date: 2026-10-21

## Change

HLS verification and Media3 now share one media-header builder: the existing player
User-Agent, `Accept: */*`, and the video's canonical page as `Referer`, including
when the playlist is hosted on a separate CDN. Short-ID metadata is resolved to
the full UUID before constructing this page URL.

Discovery and video-metadata requests retain their existing API User-Agent and
Accept values and do not receive a video Referer. All PeerTube requests use the
same cookie-free scope as playback. Other sources and decoder/track selection
were not changed. There is no new application dependency.

## Automated checks

- 71 Java regression tests passed, including 11 new tests covering header
  forwarding, canonical/short-ID pages, separate audio, direct-stream fallback,
  real loopback HTTP requests, redirects, cookie isolation, body limits, and
  expired deadlines.
- 14 Python prototype tests passed.
- Release lint and debug/minified unsigned-release builds passed. The application
  version was not changed; nothing was published.

## Live A/B check

`PeerTubeHeaderProbe.java` runs the production search and resolver twice on a
shared in-memory API snapshot. Only the HLS request headers differ: the old API
headers versus the shared playback headers. It checks retained cards, HLS
variants, selected video/audio URLs, and small media byte ranges. It does not
download full films or measure device playback performance.

| Query | Cards before → after | Media checks with new headers |
| --- | --- | --- |
| Солярис | 1 → 1 | HLS video/init segment and separate audio: HTTP 206 |
| Кин-дза-дза | 2 → 2 | Both origins: HLS video/init segments and separate audio: HTTP 206 |
| Андрей Рублев | 1 → 1 | HLS video/init segment: HTTP 206; separate audio file: HTTP 404 with both old and new playback headers |
| Отец солдата | 0 → 0 | No matching indexed film |

No cards or stream selections were lost. All 12 HLS comparisons (including
repeated resolution for video and audio) returned the same parsed variants.
Seven media modes succeeded; one existing source-side failure was confirmed:
`tube.azbyka.ru/static/web-videos/74a3053c-40ac-4d03-9c2a-3a813db6428d-0.mp4`
returns 404 regardless of the header change. No workaround for that unrelated
missing file was added.

These are JVM HTTP/selection checks, not an Android device or hardware-decoder
playback test. Source availability can change independently of the application.

## Reproduce

```sh
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleDebug :app:assembleRelease
python3 -m unittest research.test_peertube_search_prototype
classes=app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes
json_jar="$(rg --files "$HOME/.gradle/caches/modules-2/files-2.1/org.json/json" | rg '/json-20240303.jar$' | head -n 1)"
probe_dir="$(mktemp -d)"
javac -encoding UTF-8 -cp "$classes:$json_jar" -d "$probe_dir" research/PeerTubeHeaderProbe.java
java -cp "$probe_dir:$classes:$json_jar" ru.tubetv.app.PeerTubeHeaderProbe
```

Optional arguments replace the default four queries. The probe keeps its snapshot
in RAM only and does not change the application catalogue, state, or search indexes.
