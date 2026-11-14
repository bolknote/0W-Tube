package ru.tubetv.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class StreamResolverTest {
    @Test public void vkApiMp4FilesWorkWithoutHlsOrLegacyFallback() throws Exception {
        JSONObject files = new JSONObject().put("mp4_360", "https://cdn.example/low.mp4")
                .put("mp4_1080", "https://cdn.example/full.mp4");
        PlaybackInfo video = StreamResolver.inspectVkFiles(files, 1920, 1080, false);
        PlaybackInfo audio = StreamResolver.inspectVkFiles(files, 1920, 1080, true);
        assertEquals("https://cdn.example/full.mp4", video.streamUrl);
        assertEquals("https://cdn.example/low.mp4", audio.streamUrl);
        assertEquals(1920, video.maxWidth);
        assertEquals(1080, video.maxHeight);
    }

    @Test public void vkLegacyFileKeysStillSelectHighestQuality() throws Exception {
        JSONObject files = new JSONObject().put("url720", "https://cdn.example/720.mp4")
                .put("cache1080", "https://cdn.example/1080.mp4");
        assertEquals("https://cdn.example/1080.mp4",
                StreamResolver.inspectVkFiles(files, 0, 0, false).streamUrl);
    }

    @Test public void vkDashOnlyVideoHasAPlayableSource() throws Exception {
        JSONObject files = new JSONObject().put("dash", "https://cdn.example/master.mpd");
        PlaybackInfo info = StreamResolver.inspectVkFiles(files, 1920, 1080, false);
        assertEquals("https://cdn.example/master.mpd", info.streamUrl);
        assertEquals("application/dash+xml", info.streamMimeType);
    }

    @Test public void rutubeTextDetailTakesPrecedenceOverOldFormats() throws Exception {
        JSONObject options = new JSONObject().put("detail", "Трансляция ещё не началась")
                .put("video_balancer", new JSONObject().put("m3u8", "https://cdn.example/old.m3u8"));
        Exception error = assertThrows(Exception.class, () -> StreamResolver.requireRutubeBalancer(options));
        assertEquals("Видео RUTUBE недоступно: Трансляция ещё не началась", error.getMessage());
    }

    @Test public void rutubeDetailPrefersTheRussianMessageNotArrayOrder() throws Exception {
        JSONArray languages = new JSONArray()
                .put(new JSONObject().put("lang", "eng").put("title", "Incorrect link"))
                .put(new JSONObject().put("lang", "rus").put("title", "Неправильная ссылка на видео"));
        JSONObject options = new JSONObject().put("detail", new JSONObject().put("languages", languages));
        Exception error = assertThrows(Exception.class, () -> StreamResolver.requireRutubeBalancer(options));
        assertEquals("Видео RUTUBE недоступно: Неправильная ссылка на видео", error.getMessage());
    }

    @Test public void rutubeEmptyDetailDoesNotHideAvailableFormats() throws Exception {
        JSONObject balancer = new JSONObject().put("m3u8", "https://cdn.example/stream.m3u8");
        for (Object detail : new Object[]{"", JSONObject.NULL, new JSONObject()}) {
            JSONObject options = new JSONObject().put("detail", detail).put("video_balancer", balancer);
            assertSame(balancer, StreamResolver.requireRutubeBalancer(options));
        }
        Exception error = assertThrows(Exception.class, () -> StreamResolver.requireRutubeBalancer(
                new JSONObject().put("video_balancer", new JSONObject())));
        assertEquals("RUTUBE не отдал поток", error.getMessage());
    }
}
