package ru.tubetv.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;

public final class PeerTubeClientTest {
    @Test
    public void acceptsWholeRussianFilmNearStart() {
        PeerTubeClient.Evaluation result = PeerTubeClient.evaluate(
                "Кин-дза-дза фильм",
                "Кин-дза-дза! (FullHD, комедия, 1986)",
                7644);
        assertTrue(result.accepted);
        assertTrue(PeerTubeClient.evaluate("Кин-дза-дза", "Фильм Кин-дза-дза 1986", 7644).accepted);
    }

    @Test
    public void rejectsDocumentaryAboutTheTitleInsteadOfTheFilm() {
        assertFalse(PeerTubeClient.evaluate("Андрей Рублев", "Загадка Андрея Рублёва", 2638).accepted);
    }

    @Test
    public void acceptsTransliteratedExactTitle() {
        assertTrue(PeerTubeClient.evaluate("Солярис", "Solaris", 10014).accepted);
    }

    @Test
    public void acceptsSingleWordFollowedOnlyByYear() {
        assertTrue(PeerTubeClient.evaluate("Солярис", "Solaris 1972", 10014).accepted);
    }

    @Test
    public void rejectsKeywordInsideUnrelatedLongVideo() {
        assertFalse(PeerTubeClient.evaluate(
                "Солярис",
                "Нервное сентября, Против Ома нет приема, Утомленные солярием",
                12882).accepted);
    }

    @Test
    public void rejectsShortAndTrailerResults() {
        assertFalse(PeerTubeClient.evaluate("Курьер", "Курьер робот", 46).accepted);
        assertFalse(PeerTubeClient.evaluate(
                "Отец солдата", "Отец солдата — трейлер", 5400).accepted);
    }

    @Test
    public void parsesMuxedHlsAndMaximumQuality() {
        PeerTubeClient.HlsInfo info = PeerTubeClient.parseHls(
                "https://video.example/master.m3u8",
                "#EXTM3U\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=300000,RESOLUTION=640x360,"
                        + "CODECS=\"avc1.4d401e,mp4a.40.2\"\n360.m3u8\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1920x1080,"
                        + "CODECS=\"avc1.640028,mp4a.40.2\"\n1080.m3u8\n");

        assertTrue(info.valid);
        assertTrue(info.muxed);
        assertEquals(1920, info.maxWidth);
        assertEquals(1080, info.maxHeight);
        assertEquals("https://video.example/360.m3u8", info.lowestMuxedUrl);
    }

    @Test
    public void findsSeparateHlsAudioRendition() {
        PeerTubeClient.HlsInfo info = PeerTubeClient.parseHls(
                "https://video.example/path/master.m3u8",
                "#EXTM3U\n"
                        + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"stereo\",NAME=\"Stereo\","
                        + "DEFAULT=YES,URI=\"audio.m3u8\"\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720,"
                        + "CODECS=\"avc1.64001f\",AUDIO=\"stereo\"\nvideo.m3u8\n");

        assertTrue(info.valid);
        assertFalse(info.muxed);
        assertEquals("https://video.example/path/audio.m3u8", info.separateAudioUrl);
    }

    @Test
    public void audioModeStillPrefersSeparateTrackAfterExpandedSearch() throws Exception {
        PlaybackInfo audio = PeerTubeClient.resolve("https://video.example/api/v1/videos/test", true, true,
                (address, limit, deadline, headers) -> "{\"duration\":5400,\"files\":["
                        + "{\"fileUrl\":\"/video.mp4\",\"width\":1280,\"height\":720,"
                        + "\"hasVideo\":true,\"hasAudio\":true},"
                        + "{\"fileUrl\":\"/audio.mp4\",\"resolution\":{\"id\":0},"
                        + "\"hasVideo\":false,\"hasAudio\":true,\"size\":4096}]}");
        assertEquals("https://video.example/audio.mp4", audio.streamUrl);
        assertEquals("audio/mp4", audio.streamMimeType);
    }

    @Test
    public void manifestProbeUsesPlaybackHeadersAndCanonicalVideoPage() throws Exception {
        String uuid = "bde0ec8d-dbbe-464f-b61e-ef32d8939393";
        String page = "https://films.example/videos/watch/" + uuid;
        AtomicInteger probes = new AtomicInteger();
        PeerTubeClient.TextReader reader = (address, limit, deadline, headers) -> {
            if (address.contains("/api/v1/videos/")) {
                assertEquals(PeerTubeClient.apiHeaders(), headers);
                assertFalse(headers.containsKey("Referer"));
                return "{\"uuid\":\"" + uuid + "\",\"duration\":7644,\"streamingPlaylists\":["
                        + "{\"playlistUrl\":\"https://cdn.example/master.m3u8\"}]}";
            }
            assertEquals("https://cdn.example/master.m3u8", address);
            assertEquals(PeerTubeClient.mediaHeaders(page), headers);
            assertEquals(PeerTubeClient.MEDIA_USER_AGENT, headers.get("User-Agent"));
            assertEquals("*/*", headers.get("Accept"));
            assertEquals(64 * 1024, limit);
            probes.incrementAndGet();
            return separateAudioManifest();
        };
        // A short-ID lookup must probe with the canonical page later passed to Media3.
        String api = "https://films.example/api/v1/videos/abcdefghijLMNOPQRST123";
        PlaybackInfo video = PeerTubeClient.resolve(api, false, true, reader);
        PlaybackInfo audio = PeerTubeClient.resolve(api, true, true, reader);
        assertEquals("https://cdn.example/master.m3u8", video.streamUrl);
        assertEquals("https://cdn.example/audio.m3u8", audio.streamUrl);
        assertEquals("application/x-mpegURL", audio.streamMimeType);
        assertEquals(1280, video.maxWidth);
        assertEquals(2, probes.get());
    }

    @Test
    public void manifestProbeFallsBackToRequestedIdWhenMetadataHasNoUuid() throws Exception {
        PlaybackInfo info = PeerTubeClient.resolve("https://films.example:8443/api/v1/videos/test",
                false, true, (address, limit, deadline, headers) -> {
                    if (address.contains("/api/v1/videos/")) {
                        return "{\"streamingPlaylists\":[{\"playlistUrl\":\"/master.m3u8\"}]}";
                    }
                    assertEquals("https://films.example:8443/videos/watch/test", headers.get("Referer"));
                    return separateAudioManifest();
                });
        assertEquals("https://films.example:8443/master.m3u8", info.streamUrl);
    }

    @Test
    public void failedManifestProbeDoesNotRemoveDirectVideoOrAudio() throws Exception {
        PeerTubeClient.TextReader reader = (address, limit, deadline, headers) -> {
            if (!address.contains("/api/v1/videos/")) throw new IOException("CDN unavailable");
            return "{\"files\":["
                    + "{\"fileUrl\":\"/video.mp4\",\"width\":1920,\"height\":1080,"
                    + "\"hasVideo\":true,\"hasAudio\":true},"
                    + "{\"fileUrl\":\"/audio.mp4\",\"resolution\":{\"id\":0},"
                    + "\"hasVideo\":false,\"hasAudio\":true,\"size\":4096}],"
                    + "\"streamingPlaylists\":[{\"playlistUrl\":\"/master.m3u8\"}]}";
        };
        String api = "https://films.example/api/v1/videos/test";
        assertEquals("https://films.example/video.mp4",
                PeerTubeClient.resolve(api, false, true, reader).streamUrl);
        assertEquals("https://films.example/audio.mp4",
                PeerTubeClient.resolve(api, true, true, reader).streamUrl);
    }

    @Test
    public void discoveryKeepsApiHeadersWithoutAVideoReferer() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        List<VideoItem> result = PeerTubeClient.search("Солярис", 0, 320,
                (address, limit, deadline, headers) -> {
                    assertEquals(PeerTubeClient.apiHeaders(), headers);
                    assertFalse(headers.containsKey("Referer"));
                    requests.incrementAndGet();
                    return "{\"data\":[],\"videos\":[]}";
                });
        assertTrue(result.isEmpty());
        assertTrue(requests.get() >= 4);
    }

    @Test
    public void verifiedCardAndManifestProbeUseTheSameReferer() throws Exception {
        String uuid = "bde0ec8d-dbbe-464f-b61e-ef32d8939393";
        PeerTubeSearch.Candidate candidate = PeerTubeSearch.Candidate.from(new JSONObject()
                .put("videoUrl", "https://films.example/w/abcdefghijLMNOPQRST123")
                .put("title", "Кин-дза-дза").put("durationSeconds", 7644), true);
        Map<String, String> expected = PeerTubeClient.mediaHeaders(
                "https://films.example/videos/watch/" + uuid);
        VideoItem item = PeerTubeClient.inspectCandidate(candidate, 0, 320,
                (address, limit, deadline, headers) -> {
                    if (address.contains("/api/v1/videos/")) {
                        return "{\"uuid\":\"" + uuid + "\",\"duration\":7644,\"streamingPlaylists\":["
                                + "{\"playlistUrl\":\"https://cdn.example/master.m3u8\"}]}";
                    }
                    assertEquals(expected, headers);
                    return separateAudioManifest();
                }, Long.MAX_VALUE);
        assertEquals(expected, PeerTubeClient.mediaHeaders(item.pageUrl));
    }

    private static String separateAudioManifest() {
        return "#EXTM3U\n"
                + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"stereo\",NAME=\"Stereo\","
                + "DEFAULT=YES,URI=\"audio.m3u8\"\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720,"
                + "CODECS=\"avc1.64001f\",AUDIO=\"stereo\"\nvideo.m3u8\n";
    }

    @Test
    public void normalizesYoAndTransliteratesTitle() {
        assertEquals("полет к звездам", PeerTubeClient.normalize("Полёт к звёздам"));
        assertEquals("kin dza dza", PeerTubeClient.transliterate("Кин-дза-дза"));
    }

    @Test
    public void globalRankerRecognizesLatinPeerTubeTitle() {
        int peerTube = VideoRanker.score("Solaris", "солярис");
        int unrelated = VideoRanker.score("Нервное сентября", "солярис");
        assertTrue(peerTube >= 90_000);
        assertTrue(peerTube > unrelated);
    }
}
