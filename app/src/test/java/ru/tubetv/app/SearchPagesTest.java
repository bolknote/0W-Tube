package ru.tubetv.app;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class SearchPagesTest {
    @Test public void rutubeRetainsTheWholePageRatherThanOnlyTwelveCards() throws Exception {
        JSONArray rows = new JSONArray();
        for (int i = 0; i < 30; i++) rows.put(new JSONObject().put("id", "video" + i)
                .put("title", "Видео " + i).put("duration", 180));
        rows.put(JSONObject.NULL).put("not a card");
        JSONObject data = new JSONObject().put("results", rows)
                .put("next", "/api/search/video?query=test&page=2&per_page=30");

        PagedSearchSession.Page page = SearchClient.parseRutubePage(data,
                "https://rutube.ru/api/search/video/?query=test&page=1&limit=30");

        assertEquals(30, page.items.size());
        assertEquals("Видео 29", page.items.get(29).title);
        assertEquals(180_000L, page.items.get(29).durationMs);
        assertEquals("https://rutube.ru/api/search/video?query=test&page=2&per_page=30", page.next);
    }

    @Test public void dzenRetainsTheFullJsonPageAndItsDimensions() throws Exception {
        JSONArray rows = new JSONArray();
        for (int i = 0; i < 25; i++) rows.put(new JSONObject()
                .put("link", "https://dzen.ru/video/watch/video" + i)
                .put("title", "Видео " + i)
                .put("video", new JSONObject().put("width", 1920).put("height", 1080)
                        .put("duration", 600).put("resolutions", new JSONArray()
                                .put(new JSONObject().put("width", 3840).put("height", 2160)))));
        JSONObject data = new JSONObject().put("feedData", new JSONObject().put("items", rows)
                .put("more", new JSONObject().put("link", "https://dzen.ru/api/search?cursor=next")));

        PagedSearchSession.Page page = DzenClient.parseJsonPage(data, "https://dzen.ru/api/search");

        assertEquals(25, page.items.size());
        assertEquals("Видео 24", page.items.get(24).title);
        assertEquals(3840, page.items.get(24).maxWidth);
        assertEquals(600_000L, page.items.get(24).durationMs);
        assertEquals("https://dzen.ru/api/search?cursor=next", page.next);
    }

    @Test public void dzenDeduplicatesAndRejectsNextLinksOutsideTheSource() throws Exception {
        JSONObject row = new JSONObject().put("link", "https://dzen.ru/video/watch/abc123")
                .put("title", "Видео").put("video", new JSONObject());
        JSONObject data = new JSONObject().put("items", new JSONArray().put(row).put(row))
                .put("more", new JSONObject().put("link", "https://elsewhere.example/search"));

        PagedSearchSession.Page page = DzenClient.parseJsonPage(data, "https://dzen.ru/api/search");

        assertEquals(1, page.items.size());
        assertNull(page.next);
    }

    @Test public void okRetainsThirteenthNativeCardAndCountsAllRowsInOffset() throws Exception {
        JSONArray rows = new JSONArray();
        for (int i = 0; i < 20; i++) rows.put(okCard(i));
        rows.getJSONObject(1).getJSONObject("movie").put("provider", "USER_YOUTUBE");
        rows.getJSONObject(2).getJSONObject("movie").put("blocked", true);
        rows.put(okCard(19));
        JSONObject props = new JSONObject().put("videos", new JSONObject()
                .put("list", rows).put("hasMore", true));

        PagedSearchSession.Page page = OkClient.parseSearchPage(props, 20);

        assertEquals(18, page.items.size());
        assertEquals("Видео 19", page.items.get(17).title);
        assertEquals("41", page.next);
        assertEquals(3840, page.items.get(17).maxWidth);
    }

    @Test public void okUsesTheWebsitePagingParametersInsteadOfGuessingPageNumbers() throws Exception {
        JSONObject state = new JSONObject().put("searchQuery", "Интерстеллар")
                .put("currentStateId", "anonymVideo").put("filters", new JSONObject()
                        .put("durationType", "LONG").put("hdQuality", true).put("createdType", "ANY"));

        JSONObject parameters = OkClient.searchParameters(state, "fallback", 20, 3);

        assertEquals("Movie", parameters.getString("displayMode"));
        assertEquals(20, parameters.getInt("videosOffset"));
        assertEquals(3, parameters.getInt("channelsOffset"));
        assertEquals("Интерстеллар", parameters.getString("searchQuery"));
        assertEquals("anonymVideo", parameters.getString("currentStateId"));
        assertEquals("LONG", parameters.getString("durationType"));
        assertTrue(parameters.getBoolean("hd"));
        assertEquals("ANY", parameters.getString("createdType"));
    }

    @Test public void okEmptyOrFinalPageStopsAndApiErrorsAreNotSuccessfulEmptyPages() throws Exception {
        JSONObject videos = new JSONObject().put("list", new JSONArray()).put("hasMore", true);
        assertNull(OkClient.parseSearchPage(new JSONObject().put("videos", videos), 20).next);
        videos.put("list", new JSONArray().put(okCard(0))).put("hasMore", false);
        JSONObject data = new JSONObject().put("videos", videos);
        assertNull(OkClient.parseSearchPage(data, 20).next);
        assertSame(data, OkClient.searchResponse(new JSONObject().put("success", true).put("result", data)));
        assertThrows(Exception.class, () -> OkClient.searchResponse(new JSONObject().put("success", false)));
        assertThrows(Exception.class, () -> OkClient.searchResponse(new JSONObject().put("success", true)));
    }

    @Test public void okInitialHtmlCanBeParsedWithoutDiscardingItsTail() throws Exception {
        JSONArray rows = new JSONArray();
        for (int i = 0; i < 20; i++) rows.put(okCard(i));
        JSONObject data = new JSONObject().put("videos", new JSONObject().put("list", rows));
        String html = "<video-search-result data-props=\""
                + data.toString().replace("&", "&amp;").replace("\"", "&quot;")
                + "\"></video-search-result>";

        assertEquals(12, OkClient.parseSearchResults(html).size());
        assertEquals(20, OkClient.parseSearchPage(OkClient.parseSearchProps(html), 0).items.size());
    }

    private static JSONObject okCard(int id) throws Exception {
        return new JSONObject().put("name", "Видео " + id)
                .put("movie", new JSONObject().put("id", Long.toString(123456789000L + id))
                        .put("provider", "UPLOADED_ODKL").put("width", 3840).put("height", 2160));
    }
}
