package ru.tubetv.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public final class OkClientTest {
    @Test
    public void parsesNativeSearchCardsAndSkipsExternalProviders() throws Exception {
        JSONObject nativeMovie = new JSONObject()
                .put("id", "2592237357790")
                .put("provider", "UPLOADED_ODKL")
                .put("title", "Солярис")
                .put("duration", 10_020_000L)
                .put("width", 1280)
                .put("height", 536);
        JSONObject nativeCard = new JSONObject()
                .put("name", "Солярис — Андрей Тарковский")
                .put("imageUrl", "https://i.mycdn.me/preview?a=1&b=2")
                .put("movie", nativeMovie);
        JSONObject youtubeCard = new JSONObject()
                .put("name", "Внешнее видео")
                .put("movie", new JSONObject()
                        .put("id", "3952212382174")
                        .put("provider", "USER_YOUTUBE"));
        JSONObject props = new JSONObject().put("videos", new JSONObject()
                .put("list", new JSONArray().put(nativeCard).put(youtubeCard)));

        String html = "<video-search-result data-props=\""
                + htmlAttribute(props.toString()) + "\"></video-search-result>";
        List<VideoItem> result = OkClient.parseSearchResults(html);

        assertEquals(1, result.size());
        VideoItem video = result.get(0);
        assertEquals("OK", video.source);
        assertEquals("Солярис — Андрей Тарковский", video.title);
        assertEquals("https://ok.ru/videoembed/2592237357790", video.playUrl);
        assertEquals("https://ok.ru/video/2592237357790", video.pageUrl);
        assertEquals("https://i.mycdn.me/preview?a=1&b=2", video.thumbnail);
        assertEquals(10_020_000L, video.durationMs);
        assertEquals(1280, video.maxWidth);
        assertEquals(536, video.maxHeight);
    }

    @Test
    public void parsesHtmlEncodedInlinePlayerMetadata() throws Exception {
        JSONObject metadata = new JSONObject()
                .put("provider", "UPLOADED_ODKL")
                .put("movie", new JSONObject()
                        .put("id", "2592237357790")
                        .put("title", "Солярис"))
                .put("hlsManifestUrl", "https://vd123.mycdn.me/master.m3u8")
                .put("ondemandDash", "https://vd123.mycdn.me/manifest.mpd")
                .put("videos", new JSONArray().put(new JSONObject()
                        .put("name", "low")
                        .put("url", "https://vd123.mycdn.me/video?type=1")));
        JSONObject player = new JSONObject().put("flashvars",
                new JSONObject().put("metadata", metadata.toString()));
        String html = "<div data-options=\"" + htmlAttribute(player.toString()) + "\"></div>";

        OkClient.PlayerData data = OkClient.parseDesktopPlayer("2592237357790", html);

        assertEquals("2592237357790", data.id);
        assertNotNull(data.metadata);
        assertEquals("Солярис", data.metadata.getJSONObject("movie").getString("title"));
        assertNull(data.externalUrl);
        assertNull(data.mobileUrl);
    }

    @Test
    public void parsesInlinePlayerMetadataObject() throws Exception {
        JSONObject metadata = new JSONObject()
                .put("provider", "UPLOADED_ODKL")
                .put("movie", new JSONObject()
                        .put("id", "17367842097827")
                        .put("title", "Объектные метаданные"))
                .put("hlsManifestUrl", "https://vd123.mycdn.me/master.m3u8");
        JSONObject player = new JSONObject().put("flashvars",
                new JSONObject().put("metadata", metadata));
        String html = "<div data-options=\"" + htmlAttribute(player.toString()) + "\"></div>";

        OkClient.PlayerData data = OkClient.parseDesktopPlayer("17367842097827", html);

        assertEquals("17367842097827", data.id);
        assertNotNull(data.metadata);
        assertEquals("Объектные метаданные",
                data.metadata.getJSONObject("movie").getString("title"));
        assertNull(data.externalUrl);
        assertNull(data.mobileUrl);
    }

    @Test
    public void parsesExternalAndMobileFallbackPlayers() throws Exception {
        JSONObject external = new JSONObject()
                .put("isExternalPlayer", true)
                .put("url", "//vkvideo.ru/video-1_2")
                .put("videoId", "2932705602075");
        String desktop = "<div data-options=\""
                + htmlAttribute(external.toString()) + "\"></div>";
        OkClient.PlayerData externalData =
                OkClient.parseDesktopPlayer("2932705602075", desktop);
        assertEquals("https://vkvideo.ru/video-1_2", externalData.externalUrl);

        JSONObject mobile = new JSONObject()
                .put("videoSrc", "http://127.0.0.1:1/video.mp4")
                .put("videoName", "Мобильное видео");
        String mobileHtml = "<div data-video=\""
                + htmlAttribute(mobile.toString()) + "\"></div>";
        OkClient.PlayerData mobileData =
                OkClient.parseMobilePlayer("2361249957145", mobileHtml);
        assertEquals("http://127.0.0.1:1/video.mp4", mobileData.mobileUrl);
    }

    @Test
    public void recognizesAllYtDlpUrlForms() throws Exception {
        assertEquals("20079905452",
                OkClient.findVideoId("https://ok.ru/web-api/video/moviePlayer/20079905452"));
        assertEquals("63567059965189-0",
                OkClient.findVideoId("https://ok.ru/video/63567059965189-0?fromTime=5"));
        assertEquals("484531969818",
                OkClient.findVideoId("https://www.ok.ru/live/484531969818"));
        assertEquals("863789452017",
                OkClient.findVideoId("https://m.ok.ru/dk?st.cmd=movieLayer&st.mvId=863789452017"));
        assertTrue(OkClient.isOkUrl("https://www.odnoklassniki.ru/videoembed/20648036891"));
        assertFalse(OkClient.isOkUrl("https://example.com/video/20648036891"));
    }

    @Test
    public void decodesNamedDecimalAndHexEntities() {
        assertEquals("\"Солярис\" & OK — А",
                OkClient.decodeHtml("&quot;Солярис&quot; &amp; OK &#8212; &#x410;"));
    }

    private static String htmlAttribute(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
