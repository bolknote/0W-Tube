package ru.tubetv.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class VkSearchSessionTest {
    @Test
    public void qualityFilterFindsCandidateAfterOrdinaryTwelveCardCutoff() throws Exception {
        List<String> requests = new ArrayList<>();
        VkWebClient.SearchSession session = new VkWebClient.SearchSession(320, (section, next) -> {
            requests.add(section + ":" + next);
            int number = requests.size();
            JSONObject page = page((number - 1) * 10, 10, 1080, "p" + (number + 1));
            if (number == 1) video(page, 3).put("height", 2160).put("width", 3840);
            if (number == 2) video(page, 7).put("height", 1440).put("width", 2560);
            return page;
        });
        List<VideoItem> cards = new ArrayList<>();

        assertEquals(12, collect(session, 0, cards));
        assertEquals(2, session.requestCount());
        assertEquals(1, matching(cards, 2560));
        assertFalse(session.needsMore(0));
        assertTrue(session.needsMore(2560));

        collect(session, 2560, cards);

        assertEquals(2, matching(cards, 2560));
        assertTrue(cards.stream().anyMatch(item -> item.title.equals("Video 17")
                && item.maxWidth == 2560));
        assertEquals(40, cards.size());
        assertEquals(Arrays.asList("null:null", "search:p2", "search:p3", "search:p4"), requests);
        assertFalse(session.needsMore(2560));
    }

    @Test
    public void initialQualityFilterUsesMatchingCountInsteadOfTotalCards() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        VkWebClient.SearchSession session = new VkWebClient.SearchSession(320, (section, next) -> {
            int number = requests.incrementAndGet();
            return page((number - 1) * 10, 10, number == 1 ? 1080 : 1440, "p" + (number + 1));
        });
        List<VideoItem> cards = new ArrayList<>();

        collect(session, 2560, cards);

        assertEquals(3, requests.get());
        assertEquals(22, cards.size());
        assertEquals(12, matching(cards, 2560));
        assertFalse(session.needsMore(2560));
    }

    @Test
    public void entireFetchedPageIsRetainedForStricterFilter() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        VkWebClient.SearchSession session = new VkWebClient.SearchSession(320, (section, next) -> {
            requests.incrementAndGet();
            JSONObject page = page(0, 30, 1080, null);
            for (int i = 12; i < 30; i++) video(page, i).put("width", 2560).put("height", 1440);
            return page;
        });
        List<VideoItem> cards = new ArrayList<>();
        collect(session, 0, cards);
        assertEquals(12, cards.size());
        assertTrue(session.needsMore(2560));

        collect(session, 2560, cards);

        assertEquals(1, requests.get());
        assertEquals(24, cards.size());
        assertEquals(12, matching(cards, 2560));
        assertFalse(session.needsMore(2560));
    }

    @Test
    public void enoughMatchingCardsDoNotFetchNextPage() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        VkWebClient.SearchSession session = new VkWebClient.SearchSession(320, (section, next) -> {
            requests.incrementAndGet();
            return page(0, 30, 1440, "unused");
        });
        List<VideoItem> cards = new ArrayList<>();

        collect(session, 2560, cards);

        assertEquals(1, requests.get());
        assertEquals(12, matching(cards, 2560));
        assertFalse(session.needsMore(2560));
        assertFalse(session.needsMore(0));
    }

    @Test
    public void pageBudgetIsSharedAcrossRepeatedFilterChanges() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        VkWebClient.SearchSession session = new VkWebClient.SearchSession(320, (section, next) -> {
            int number = requests.incrementAndGet();
            return page(number * 10, 10, 720, "p" + number);
        });
        List<VideoItem> cards = new ArrayList<>();
        collect(session, 2560, cards);

        for (int width : new int[]{0, 3840, 1280, 2560, 1920, 0, 2560}) {
            assertFalse(session.needsMore(width));
            assertEquals(0, session.searchPages(width, items -> {
                fail("Previously delivered cards must not be delivered twice");
                return true;
            }));
        }

        assertEquals(4, requests.get());
        assertEquals(4, session.requestCount());
        assertEquals(40, cards.size());
    }

    @Test
    public void repeatedCursorStopsBeforeRepeatingRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        VkWebClient.SearchSession session = new VkWebClient.SearchSession(320, (section, next) -> {
            int number = requests.incrementAndGet();
            return page(number * 10, 10, 1080, number == 2 ? "b" : "a");
        });
        List<VideoItem> cards = new ArrayList<>();

        collect(session, 2560, cards);

        assertEquals(3, requests.get());
        assertEquals(30, cards.size());
        assertFalse(session.needsMore(2560));
    }

    @Test
    public void duplicateVideosDoNotSatisfyQualityTargetOrRepeatCards() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        VkWebClient.SearchSession session = new VkWebClient.SearchSession(320, (section, next) -> {
            int number = requests.incrementAndGet();
            return page(number <= 2 ? 0 : 10, 10, 1440, "p" + number);
        });
        List<VideoItem> cards = new ArrayList<>();

        collect(session, 2560, cards);

        assertEquals(3, requests.get());
        assertEquals(12, cards.size());
        HashSet<String> keys = new HashSet<>();
        for (VideoItem item : cards) assertTrue(keys.add(item.stableKey()));
    }

    @Test
    public void errorKeepsSuccessfulPagesAndDoesNotAutomaticallyRetry() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        VkWebClient.SearchSession session = new VkWebClient.SearchSession(320, (section, next) -> {
            if (requests.incrementAndGet() == 2) throw new IOException("offline");
            return page(0, 10, 1440, "p2");
        });
        List<VideoItem> cards = new ArrayList<>();

        assertThrows(IOException.class, () -> collect(session, 2560, cards));

        assertEquals(10, cards.size());
        assertEquals(2, session.requestCount());
        assertFalse(session.needsMore(2560));
        assertEquals(0, collect(session, 2560, cards));
        assertEquals(2, requests.get());
    }

    @Test
    public void listenerCanStopAndResumeWithoutReloadingFirstPage() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        VkWebClient.SearchSession session = new VkWebClient.SearchSession(320, (section, next) -> {
            int number = requests.incrementAndGet();
            return page(number * 10, 10, 1080, "p" + number);
        });
        List<VideoItem> cards = new ArrayList<>();
        assertEquals(10, session.searchPages(0, items -> {
            cards.addAll(items);
            return false;
        }));
        assertEquals(1, requests.get());

        assertEquals(2, collect(session, 0, cards));

        assertEquals(2, requests.get());
        assertEquals(12, cards.size());
    }

    @Test
    public void interruptedSearchDoesNotRequestOrPublishPages() throws Exception {
        VkWebClient.SearchSession session = new VkWebClient.SearchSession(320, (section, next) -> {
            fail("Cancelled search must not fetch pages");
            return null;
        });
        Thread.currentThread().interrupt();
        try {
            assertEquals(0, session.searchPages(2560, items -> {
                fail("Cancelled search must not publish cards");
                return true;
            }));
            assertEquals(0, session.requestCount());
        } finally {
            Thread.interrupted();
        }
    }

    private static int collect(VkWebClient.SearchSession session, int width,
                               List<VideoItem> cards) throws Exception {
        return session.searchPages(width, page -> {
            cards.addAll(page);
            return true;
        });
    }

    private static long matching(List<VideoItem> cards, int width) {
        return cards.stream().filter(item -> item.maxWidth >= width).count();
    }

    private static JSONObject page(int start, int count, int height, String next) throws Exception {
        JSONArray videos = new JSONArray();
        JSONArray ids = new JSONArray();
        for (int i = 0; i < count; i++) {
            int id = start + i;
            ids.put("-1_" + id);
            videos.put(new JSONObject().put("video", new JSONObject()
                    .put("owner_id", -1).put("id", id).put("access_key", "key")
                    .put("title", "Video " + id).put("duration", 7200)
                    .put("width", height * 16 / 9).put("height", height)));
        }
        JSONObject section = new JSONObject().put("id", "search")
                .put("blocks", new JSONArray().put(new JSONObject().put("video_ids", ids)));
        if (next != null) section.put("next_from", next);
        return new JSONObject().put("catalog_videos", videos).put("section", section);
    }

    private static JSONObject video(JSONObject page, int index) throws Exception {
        return page.getJSONArray("catalog_videos").getJSONObject(index).getJSONObject("video");
    }
}
