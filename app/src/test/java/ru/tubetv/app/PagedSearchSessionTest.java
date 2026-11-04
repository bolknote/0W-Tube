package ru.tubetv.app;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class PagedSearchSessionTest {
    @Test public void bufferedTailIsUsedBeforeRequestingAnotherPage() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        PagedSearchSession session = new PagedSearchSession(cursor -> {
            loads.incrementAndGet();
            List<VideoItem> cards = cards(0, 30, 1920);
            for (int i = 12; i < 30; i++) cards.set(i, cards.get(i).withQuality(2560, 1440));
            return new PagedSearchSession.Page(cards, "unused");
        }, item -> { throw new AssertionError("Dimensions are already known"); });
        List<VideoItem> found = new ArrayList<>();

        assertEquals(12, collect(session, 0, found));
        assertTrue(session.needsMore(2560));
        assertEquals(12, collect(session, 2560, found));

        assertEquals(1, loads.get());
        assertEquals(12, matching(found, 2560));
        assertFalse(session.needsMore(2560));
        assertEquals(0, session.qualityProbeCount());
    }

    @Test public void qualityTargetCountsMatchingCardsRatherThanAllCards() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        List<String> cursors = new ArrayList<>();
        PagedSearchSession session = new PagedSearchSession(cursor -> {
            cursors.add(cursor);
            int page = loads.incrementAndGet();
            return new PagedSearchSession.Page(cards((page - 1) * 10, 10,
                    page == 1 ? 1920 : 2560), "p" + (page + 1));
        });
        List<VideoItem> found = new ArrayList<>();

        collect(session, 2560, found);

        assertEquals(Arrays.asList(null, "p2", "p3"), cursors);
        assertEquals(22, found.size());
        assertEquals(12, matching(found, 2560));
    }

    @Test public void ordinarySearchDefersInspectionAndWaitsForItsCompletion() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger probes = new AtomicInteger();
        PagedSearchSession session = new PagedSearchSession(cursor -> {
            loads.incrementAndGet();
            return new PagedSearchSession.Page(cards(0, 30, 0), "unused");
        }, item -> {
            probes.incrementAndGet();
            return item.withQuality(2560, 1440);
        });
        List<VideoItem> found = new ArrayList<>();
        collect(session, 0, found);
        assertEquals(12, found.size());
        assertEquals(0, probes.get());
        assertEquals(12, session.qualityProbeCount());
        for (VideoItem item : found) assertTrue(session.allowsQualityProbe(item));
        assertFalse(session.needsMore(2560));
        assertEquals(0, collect(session, 2560, new ArrayList<>()));

        session.completeQualities(found, withQuality(found, 1920));
        collect(session, 2560, found);

        assertEquals(1, loads.get());
        assertEquals(12, probes.get());
        assertEquals(24, session.qualityProbeCount());
        assertEquals(12, matching(found, 2560));
        assertFalse(session.needsMore(2560));
    }

    @Test public void completedOrdinaryCardsCanAlreadySatisfyTheQualityTarget() throws Exception {
        PagedSearchSession session = new PagedSearchSession(cursor ->
                new PagedSearchSession.Page(cards(0, 30, 0), "unused"));
        List<VideoItem> found = new ArrayList<>();
        collect(session, 0, found);

        session.completeQualities(found, withQuality(found, 3840));

        assertFalse(session.needsMore(3840));
        assertEquals(1, session.requestCount());
        for (VideoItem item : found) assertFalse(session.allowsQualityProbe(item));
    }

    @Test public void failedOrdinaryInspectionsUnblockContinuationWithoutDuplicateProbes() throws Exception {
        AtomicInteger probes = new AtomicInteger();
        PagedSearchSession session = new PagedSearchSession(cursor ->
                new PagedSearchSession.Page(cards(0, 20, 0), null), item -> {
            probes.incrementAndGet();
            return item.withQuality(2560, 1440);
        });
        List<VideoItem> found = new ArrayList<>();
        collect(session, 0, found);
        assertFalse(session.needsMore(2560));

        session.completeQualities(found, new ArrayList<>());
        assertTrue(session.needsMore(2560));
        collect(session, 2560, found);

        assertEquals(8, probes.get());
        assertEquals(20, found.size());
        assertEquals(8, matching(found, 2560));
        assertFalse(session.needsMore(2560));
    }

    @Test public void pageBudgetIsSharedAcrossFilterChanges() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        PagedSearchSession session = new PagedSearchSession(cursor -> {
            int page = loads.incrementAndGet();
            return new PagedSearchSession.Page(cards(page * 10, 10, 1280), "p" + page);
        });
        List<VideoItem> found = new ArrayList<>();
        collect(session, 2560, found);

        for (int width : new int[]{0, 3840, 1920, 2560, 1280, 0}) {
            assertFalse(session.needsMore(width));
            assertEquals(0, collect(session, width, found));
        }
        assertEquals(4, loads.get());
        assertEquals(4, session.requestCount());
        assertEquals(40, found.size());
    }

    @Test public void qualityProbeBudgetIncludesOrdinaryCardsAndIsNeverReset() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger probes = new AtomicInteger();
        PagedSearchSession session = new PagedSearchSession(cursor -> {
            int page = loads.incrementAndGet();
            return new PagedSearchSession.Page(cards((page - 1) * 30, 30, 0), "p" + page);
        }, item -> {
            probes.incrementAndGet();
            return item.withQuality(1280, 720);
        });
        List<VideoItem> found = new ArrayList<>();
        collect(session, 0, found);
        session.completeQualities(found, withQuality(found, 1280));
        collect(session, 2560, found);

        assertEquals(48, session.qualityProbeCount());
        assertEquals(36, probes.get());
        assertEquals(2, loads.get());
        assertEquals(60, found.size());
        for (int width : new int[]{0, 1920, 3840, 2560}) {
            assertFalse(session.needsMore(width));
            assertEquals(0, collect(session, width, found));
        }
        for (VideoItem item : found) assertFalse(session.allowsQualityProbe(item));
        assertEquals(36, probes.get());
    }

    @Test public void knownDimensionsNeedNoProbesAndCanUseAllFourPages() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        PagedSearchSession session = new PagedSearchSession(cursor -> {
            int page = loads.incrementAndGet();
            List<VideoItem> cards = cards((page - 1) * 30, 30, 1920);
            if (page == 4) cards.set(29, cards.get(29).withQuality(3840, 2160));
            return new PagedSearchSession.Page(cards, "p" + page);
        }, item -> { throw new AssertionError("Unexpected quality request"); });

        List<VideoItem> found = session.search(3840);

        assertEquals(1, found.size());
        assertEquals("Video 119", found.get(0).title);
        assertEquals(4, session.requestCount());
        assertEquals(0, session.qualityProbeCount());
    }

    @Test public void duplicateVideosDoNotCountTwiceOrAppearTwice() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        PagedSearchSession session = new PagedSearchSession(cursor -> {
            int page = loads.incrementAndGet();
            return new PagedSearchSession.Page(cards(page <= 2 ? 0 : 10, 10, 2560), "p" + page);
        });
        List<VideoItem> found = new ArrayList<>();
        collect(session, 2560, found);

        HashSet<String> keys = new HashSet<>();
        for (VideoItem item : found) assertTrue(keys.add(item.stableKey()));
        assertEquals(12, found.size());
        assertEquals(3, loads.get());
    }

    @Test public void repeatedCursorStopsWithoutRepeatingTheRequest() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        PagedSearchSession session = new PagedSearchSession(cursor -> {
            int page = loads.incrementAndGet();
            return new PagedSearchSession.Page(cards(page * 2, 2, 1920), page == 2 ? "b" : "a");
        });

        assertEquals(0, session.search(2560).size());
        assertEquals(3, loads.get());
        assertFalse(session.needsMore(2560));
    }

    @Test public void emptyPagesDoNotLoseTheirCursorOrBypassThePageBudget() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        PagedSearchSession session = new PagedSearchSession(cursor -> {
            int page = loads.incrementAndGet();
            return new PagedSearchSession.Page(new ArrayList<>(), "p" + page);
        });

        assertEquals(0, session.search(2560).size());
        assertEquals(4, loads.get());
        assertFalse(session.needsMore(2560));
    }

    @Test public void laterPageErrorKeepsDeliveredCardsAndDoesNotRetryForever() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        PagedSearchSession session = new PagedSearchSession(cursor -> {
            if (loads.incrementAndGet() > 1) throw new IOException("offline");
            return new PagedSearchSession.Page(cards(0, 10, 2560), "p2");
        });
        List<VideoItem> found = new ArrayList<>();

        assertThrows(IOException.class, () -> collect(session, 2560, found));

        assertEquals(10, found.size());
        assertFalse(session.needsMore(2560));
        assertEquals(0, collect(session, 2560, found));
        assertEquals(2, loads.get());
    }

    @Test public void brokenQualityProbesAreNotRetriedOnFilterChanges() throws Exception {
        AtomicInteger probes = new AtomicInteger();
        PagedSearchSession session = new PagedSearchSession(cursor ->
                new PagedSearchSession.Page(cards(0, 3, 0), null), item -> {
            probes.incrementAndGet();
            throw new IOException("deleted video");
        });
        List<VideoItem> found = new ArrayList<>();
        collect(session, 2560, found);

        assertEquals(3, found.size());
        assertEquals(3, probes.get());
        assertFalse(session.needsMore(1920));
        for (VideoItem item : found) assertFalse(session.allowsQualityProbe(item));
        assertEquals(0, collect(session, 1920, found));
        assertEquals(3, probes.get());
    }

    @Test public void listenerCanStopAndResumeWithTheSameCursor() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        PagedSearchSession session = new PagedSearchSession(cursor -> {
            int page = loads.incrementAndGet();
            return new PagedSearchSession.Page(cards(page * 10, 10, 1920), "p" + page);
        });
        List<VideoItem> found = new ArrayList<>();
        assertEquals(10, session.searchPages(0, page -> {
            found.addAll(page);
            return false;
        }));

        assertEquals(2, collect(session, 0, found));
        assertEquals(2, loads.get());
        assertEquals(12, found.size());
    }

    @Test public void candidateBufferIsBoundedEvenIfTheServerIgnoresPageSize() throws Exception {
        PagedSearchSession session = new PagedSearchSession(cursor ->
                new PagedSearchSession.Page(cards(0, 200, 1920), "unused"));
        List<VideoItem> found = new ArrayList<>();

        collect(session, 2560, found);

        assertEquals(120, found.size());
        assertEquals(1, session.requestCount());
        assertFalse(session.needsMore(2560));
    }

    @Test public void cancelledSearchDoesNotFetchOrPublish() throws Exception {
        PagedSearchSession session = new PagedSearchSession(cursor -> {
            throw new AssertionError("Cancelled search fetched a page");
        });
        Thread.currentThread().interrupt();
        try {
            assertEquals(0, session.searchPages(2560, page -> {
                throw new AssertionError("Cancelled search published cards");
            }));
            assertEquals(0, session.requestCount());
        } finally {
            Thread.interrupted();
        }
    }

    @Test public void nextLinksMustStayOnTheSameHttpsOrigin() {
        String base = "https://dzen.ru/api/search?page=1";
        assertEquals("https://dzen.ru/api/search?page=2",
                PagedSearchSession.sameOriginNext("/api/search?page=2", base));
        assertNull(PagedSearchSession.sameOriginNext("http://dzen.ru/api/search", base));
        assertNull(PagedSearchSession.sameOriginNext("https://evil.example/api/search", base));
        assertNull(PagedSearchSession.sameOriginNext("https://dzen.ru@evil.example/", base));
        assertNull(PagedSearchSession.sameOriginNext("https://dzen.ru:8080/search", base));
        assertNull(PagedSearchSession.sameOriginNext(base, base));
        assertNull(PagedSearchSession.sameOriginNext(base + "#next", base));
        assertNull(PagedSearchSession.sameOriginNext("", base));
        assertNull(PagedSearchSession.sameOriginNext("null", base));
        assertNull(PagedSearchSession.sameOriginNext("https://[", base));
    }

    private static int collect(PagedSearchSession session, int width, List<VideoItem> found)
            throws Exception {
        return session.searchPages(width, page -> { found.addAll(page); return true; });
    }

    private static int matching(List<VideoItem> cards, int width) {
        int total = 0;
        for (VideoItem item : cards) if (item.maxWidth >= width) total++;
        return total;
    }

    private static List<VideoItem> cards(int start, int count, int width) {
        List<VideoItem> result = new ArrayList<>();
        for (int i = start; i < start + count; i++) {
            result.add(new VideoItem("RUTUBE", "Video " + i, "", "",
                    "https://example.test/play/" + i, "https://example.test/video/" + i)
                    .withQuality(width, width * 9 / 16));
        }
        return result;
    }

    private static List<VideoItem> withQuality(List<VideoItem> cards, int width) {
        List<VideoItem> result = new ArrayList<>();
        for (VideoItem card : cards) result.add(card.withQuality(width, width * 9 / 16));
        return result;
    }
}
