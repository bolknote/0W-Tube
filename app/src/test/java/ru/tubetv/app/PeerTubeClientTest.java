package ru.tubetv.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
                (address, limit, deadline) -> "{\"duration\":5400,\"files\":["
                        + "{\"fileUrl\":\"/video.mp4\",\"width\":1280,\"height\":720,"
                        + "\"hasVideo\":true,\"hasAudio\":true},"
                        + "{\"fileUrl\":\"/audio.mp4\",\"resolution\":{\"id\":0},"
                        + "\"hasVideo\":false,\"hasAudio\":true,\"size\":4096}]}");
        assertEquals("https://video.example/audio.mp4", audio.streamUrl);
        assertEquals("audio/mp4", audio.streamMimeType);
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
