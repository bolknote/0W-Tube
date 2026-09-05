package ru.tubetv.app;

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
}
