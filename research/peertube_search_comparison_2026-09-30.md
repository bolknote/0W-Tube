# PeerTube multi-source search check

Test date: 2026-09-30

## Implemented

- Search both the original title and its Latin transliteration, even if the first language succeeds.
- Combine [Sepia Search](https://sepiasearch.org/) with the independent
  [PeerSeek public JSON API](https://peerseek.video/faq).
- Use a quoted Sepia query first and an unquoted fallback for spelling variants.
- Send discovery requests only to the two search indexes. There is no fixed
  PeerTube-instance list, direct-instance search, or instance-directory dependency.
  Origin servers are taken from indexed video links and contacted only for metadata
  verification and playback resolution.
- Deduplicate origin/video identities, including `/w/` and `/videos/watch/` links.
  Resolve short-ID aliases to the full UUID from origin metadata.
- Continue past failed origin inspections: at most eight attempts, at most three cards.
- Limit discovery and inspection to two worker threads, a bounded queue, and a
  25-second total search budget. Cancellation does not wait for a blocked server.
- Keep the existing long-form/title/quality filters and audio-only stream selection.
  An arbitrary word before the title no longer passes: “Загадка Андрея Рублёва” is
  not the requested “Андрей Рублев”; a neutral prefix such as “Фильм” remains allowed.

There is no application-owned index, backend, persistent search cache, or new library dependency.

## Method

`PeerTubeSearchBenchmark.java` runs the production Java search and media inspector.
The old policy is emulated as a Russian Sepia query, the first 20 hits, and at most
three origin inspections; transliteration runs only if no playable result was found.
Both policies use the same final title filter and playback verifier, to isolate the
effect of discovery rather than count newly rejected false positives as an improvement.

For each of the 100 titles in `roskino_top100.tsv`, the tool first fetches a shared
snapshot of all bounded discovery queries. Both policies replay exactly those answers
and share metadata/master-playlist responses. No video segments or thumbnails are
downloaded. The snapshot is kept in RAM for that title only; the report contains
selected cards, contributing search hosts, and failures, not a reusable index.

This is a recall/availability comparison, not a phone performance benchmark: discovery
is prefetched and replay time does not measure normal network latency.

## Result

| Metric | Old discovery policy | New discovery policy |
| --- | ---: | ---: |
| Tested titles | 100 | 100 |
| Titles with playable results | 8 | 8 |
| Playable cards | 10 | 11 |
| Complete single-video film titles | 7 | 7 |

The eighth title, “Братья Карамазовы”, consists of three separate parts; no automatic
part classification/merging was added.

The useful extra card is a second complete “Кин-дза-дза!” on `pablo.tube` (2:12:49),
in addition to the existing 1080p copy on `peertube.terranout.mine.nu` (2:07:24).
Both appear in both global indexes, but the old policy never asks for the Latin title
after the Russian copy succeeds. PeerSeek did not add a previously unknown title
to this particular list.

“Солярис” is still found. “Отец солдата” remains absent in the tested catalogues.
There were no lost playable titles. Intermittent PeerSeek timeouts did not discard
results obtained from Sepia.

An earlier direct-instance-search experiment exposed the unrelated “Загадка Андрея
Рублёва”; this case is covered by a title-filter regression test. Direct-instance
search was removed before committing: manually chosen origins offered no extra
complete-film titles in this test and would become a stale, release-maintained list.

The main demonstrated benefits are language-union recall and fallback resilience,
not a large expansion of the current Russian-film catalogue. More fallback requests
are an intentional tradeoff; they run independently of the other application backends.

## Live checks outside the snapshot

The production HTTP path was also exercised from the local JVM:

- “Кин-дза-дза”: two playable cards; explicit audio-only files selected for both.
- “Солярис”: one playable card; explicit audio-only file selected.
- “Отец солдата”: no card.
- “Андрей Рублев”: only the film, not the unrelated documentary; audio-only file selected.

These verify public endpoint and stream-selection compatibility, not device-specific
playback or hardware decoder behavior. The player and decoder configuration were not changed.

## Reproduce

First compile the application and make the unit-test JSON dependency available:

```sh
./gradlew :app:testDebugUnitTest :app:compileDebugJavaWithJavac
json_jar="$(rg --files "$HOME/.gradle/caches/modules-2/files-2.1/org.json/json" | rg '/json-20240303.jar$' | head -n 1)"
classes="app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
javac -encoding UTF-8 -cp "$classes:$json_jar" \
  -d /tmp/owtube-peertube-benchmark research/PeerTubeSearchBenchmark.java
java -cp "/tmp/owtube-peertube-benchmark:$classes:$json_jar" \
  ru.tubetv.app.PeerTubeSearchBenchmark \
  research/roskino_top100.tsv /tmp/owtube-peertube-search-comparison.json 100
```

The last argument can be reduced for a shorter test. Endpoint availability and the
catalogue will change over time, so future counts need not match this report.
