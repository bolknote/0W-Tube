package ru.tubetv.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class PeerTubeSearchTest {
    private static final String UUID = "bde0ec8d-dbbe-464f-b61e-ef32d8939393";

    @Test
    public void peerSeekUsesVideoUrlRatherThanItsInternalId() throws Exception {
        JSONObject value = peerSeek("Кин-дза-дза", UUID).put("id", "unrelated-index-id");
        PeerTubeSearch.Candidate candidate = PeerTubeSearch.Candidate.from(value, true);
        assertNotNull(candidate);
        assertEquals("https://films.example/api/v1/videos/" + UUID, candidate.apiUrl);
        assertEquals("https://films.example/videos/watch/" + UUID, candidate.pageUrl);
        assertEquals(7644, candidate.durationSeconds);
        assertEquals("https://films.example/thumb.jpg", candidate.thumbnail);
    }

    @Test
    public void canonicalizesIndexLinksToTheSameOriginAndUuid() throws Exception {
        JSONObject first = sepia("Кин-дза-дза", UUID)
                .put("url", "https://FILMS.example:443/videos/watch/" + UUID.toUpperCase());
        JSONObject second = peerSeek("Kin-dza-dza", UUID);
        assertEquals(PeerTubeSearch.Candidate.from(first, false).apiUrl,
                PeerTubeSearch.Candidate.from(second, true).apiUrl);
        assertEquals(PeerTubeSearch.Candidate.from(first, false).pageUrl,
                PeerTubeSearch.Candidate.from(second, true).pageUrl);
    }

    @Test
    public void rejectsLiveAndMalformedIndexLinks() throws Exception {
        assertNull(PeerTubeSearch.Candidate.from(peerSeek("Кин-дза-дза", UUID)
                .put("isLive", true), true));
        assertNull(PeerTubeSearch.Candidate.from(peerSeek("Кин-дза-дза", UUID)
                .put("nsfw", true), true));
        for (String url : new String[]{"http://films.example/w/" + UUID,
                "https://user@films.example/w/" + UUID, "https://films.example/other/" + UUID,
                "https://films.example/w/../../secret"}) {
            assertNull(PeerTubeSearch.Candidate.from(peerSeek("Кин-дза-дза", UUID)
                    .put("videoUrl", url), true));
        }
    }

    @Test
    public void supportsPeerTubeShortIds() throws Exception {
        PeerTubeSearch.Candidate candidate = PeerTubeSearch.Candidate.from(
                peerSeek("Кин-дза-дза", "abcdefghijLMNOPQRST123"), true);
        assertNotNull(candidate);
        assertTrue(candidate.apiUrl.endsWith("/abcdefghijLMNOPQRST123"));
    }

    @Test
    public void aMalformedThumbnailDoesNotDiscardTheVideo() throws Exception {
        PeerTubeSearch.Candidate candidate = PeerTubeSearch.Candidate.from(
                peerSeek("Кин-дза-дза", UUID).put("thumbnailUrl", "https://bad image"), true);
        assertNotNull(candidate);
        assertNull(candidate.thumbnail);
    }

    @Test
    public void mergesRussianAndLatinHitsEvenAfterFindingTheRussianFilm() throws Exception {
        List<String> queries = Collections.synchronizedList(new ArrayList<>());
        List<VideoItem> result = PeerTubeClient.search("Кин-дза-дза", 0, 300,
                (address, limit, deadline) -> {
                    assertTrue(deadline > System.nanoTime());
                    if (address.contains("/api/v1/videos/")) return playable();
                    String query = query(address);
                    queries.add(query);
                    if (address.startsWith("https://sepiasearch.org/")) {
                        return response(false, sepia(query.contains("kin") ? "Kin-dza-dza" : "Кин-дза-дза",
                                query.contains("kin") ? id(2) : id(1)));
                    }
                    return response(address.contains("peerseek"));
                });
        assertEquals(2, result.size());
        assertTrue(queries.contains("\"кин дза дза\""));
        assertTrue(queries.contains("\"kin dza dza\""));
        assertFalse(result.get(0).playUrl.equals(result.get(1).playUrl));
    }

    @Test
    public void oneBrokenIndexDoesNotDiscardPeerSeekResultsOrCauseDuplicateInspections() throws Exception {
        List<String> inspected = Collections.synchronizedList(new ArrayList<>());
        List<VideoItem> result = PeerTubeClient.search("Солярис", 0, 300,
                (address, limit, deadline) -> {
                    if (address.contains("/api/v1/videos/")) {
                        inspected.add(address);
                        return playable();
                    }
                    if (address.contains("sepiasearch")) throw new IOException("index offline");
                    if (address.contains("peerseek")) return response(true, peerSeek("Solaris", UUID));
                    return response(false, sepia("Solaris", UUID));
                });
        assertEquals(1, result.size());
        assertEquals(1, inspected.size());
    }

    @Test
    public void originUuidDeduplicatesShortLinksWithFullUuidLinks() throws Exception {
        List<VideoItem> result = PeerTubeClient.search("Солярис", 0, 300,
                (address, limit, deadline) -> {
                    if (address.contains("/api/v1/videos/")) {
                        return new JSONObject(playable()).put("uuid", UUID).toString();
                    }
                    return address.contains("peerseek")
                            ? response(true, peerSeek("Solaris", "abcdefghijLMNOPQRST123"))
                            : response(false, sepia("Solaris", UUID));
                });
        assertEquals(1, result.size());
        assertEquals("https://films.example/api/v1/videos/" + UUID, result.get(0).playUrl);
        assertEquals("https://films.example/videos/watch/" + UUID, result.get(0).pageUrl);
    }

    @Test
    public void discoveryOnlyRequestsTheTwoSearchIndexesEvenWhenNoFilmsAreFound() throws Exception {
        List<String> requested = Collections.synchronizedList(new ArrayList<>());
        List<VideoItem> result = PeerTubeClient.search("Отец солдата", 0, 300,
                (address, limit, deadline) -> {
                    requested.add(address);
                    return response(address.contains("peerseek"));
                });
        assertTrue(result.isEmpty());
        assertEquals(6, requested.size()); // Two languages, two indexes, two unquoted Sepia fallbacks.
        for (String address : requested) {
            URI uri = URI.create(address);
            if ("sepiasearch.org".equals(uri.getHost())) {
                assertEquals("/api/v1/search/videos", uri.getPath());
                assertTrue(address.contains("nsfw=false&isLive=false&durationMin=2400"));
            } else {
                assertEquals("peerseek.video", uri.getHost());
                assertEquals("/v1/search", uri.getPath());
            }
        }
    }

    @Test
    public void triesTheFourthCandidateAfterThreeDeadOrigins() throws Exception {
        List<String> inspected = Collections.synchronizedList(new ArrayList<>());
        List<VideoItem> result = PeerTubeClient.search("Солярис", 0, 300,
                (address, limit, deadline) -> {
                    if (address.contains("/api/v1/videos/")) {
                        inspected.add(address);
                        if (!address.endsWith(id(4))) throw new IOException("dead origin");
                        return playable();
                    }
                    if (address.contains("sepiasearch")) return response(false,
                            sepia("Solaris", id(1)), sepia("Solaris", id(2)),
                            sepia("Solaris", id(3)), sepia("Solaris", id(4)));
                    return response(address.contains("peerseek"));
                });
        assertEquals(1, result.size());
        assertEquals(4, inspected.size());
        assertTrue(result.get(0).playUrl.endsWith(id(4)));
    }

    @Test
    public void enoughPlayableIndexResultsSkipTheUnquotedFallback() throws Exception {
        List<String> requested = Collections.synchronizedList(new ArrayList<>());
        List<VideoItem> result = PeerTubeClient.search("Солярис", 0, 300,
                (address, limit, deadline) -> {
                    requested.add(address);
                    if (address.contains("/api/v1/videos/")) return playable();
                    return response(false, sepia("Solaris", id(1)), sepia("Solaris", id(2)),
                            sepia("Solaris", id(3)), sepia("Solaris", id(4)));
                });
        assertEquals(3, result.size());
        for (String address : requested) {
            if (address.contains("sepiasearch")) assertTrue(query(address).startsWith("\""));
        }
        assertEquals(7, requested.size()); // Four index queries, three successful inspections.
    }

    @Test
    public void originInspectionsStayBoundedEvenWithManyDeadCandidates() throws Exception {
        List<String> inspected = Collections.synchronizedList(new ArrayList<>());
        List<VideoItem> result = PeerTubeClient.search("Солярис", 0, 300,
                (address, limit, deadline) -> {
                    if (address.contains("/api/v1/videos/")) {
                        inspected.add(address);
                        throw new IOException("dead origin");
                    }
                    if (address.contains("sepiasearch")) {
                        JSONObject[] items = new JSONObject[20];
                        for (int i = 0; i < items.length; i++) items[i] = sepia("Solaris", id(i));
                        return response(false, items);
                    }
                    return response(address.contains("peerseek"));
                });
        assertTrue(result.isEmpty());
        assertEquals(8, inspected.size());
    }

    @Test
    public void unquotedFallbackCanFindTransliterationSpellingVariants() throws Exception {
        List<VideoItem> result = PeerTubeClient.search("Солярис", 0, 300,
                (address, limit, deadline) -> {
                    if (address.contains("/api/v1/videos/")) return playable();
                    if (address.contains("sepiasearch") && query(address).equals("solyaris")) {
                        return response(false, sepia("Solaris", UUID));
                    }
                    return response(address.contains("peerseek"));
                });
        assertEquals(1, result.size());
    }

    @Test
    public void retainsTitleDurationAndQualityFilters() throws Exception {
        List<VideoItem> result = PeerTubeClient.search("Солярис", 1920, 300,
                (address, limit, deadline) -> {
                    if (address.contains("/api/v1/videos/")) return playable();
                    return response(false, sepia("Solaris", UUID), sepia("Солярис — трейлер", id(2)),
                            sepia("Solaris", id(3)).put("duration", 60));
                });
        assertTrue(result.isEmpty());
    }

    @Test
    public void metadataCanInvalidateAnOutdatedLongFilmDuration() throws Exception {
        List<VideoItem> result = PeerTubeClient.search("Солярис", 0, 300,
                (address, limit, deadline) -> address.contains("/api/v1/videos/")
                        ? new JSONObject(playable()).put("duration", 30).toString()
                        : response(false, sepia("Solaris", UUID)));
        assertTrue(result.isEmpty());
    }

    @Test
    public void interruptedSearchMakesNoRequestsAndPreservesCancellation() throws Exception {
        Thread.currentThread().interrupt();
        try {
            assertTrue(PeerTubeClient.search("Солярис", 0, 300, (address, limit, deadline) -> {
                throw new AssertionError("cancelled search made a request");
            }).isEmpty());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void cancellingAnInFlightSearchReturnsWithoutWaitingForTheServer() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        Thread search = new Thread(() -> {
            try {
                PeerTubeClient.search("Солярис", 0, 300, (address, limit, deadline) -> {
                    started.countDown();
                    release.await();
                    return response(address.contains("peerseek"));
                });
                cancelled.set(Thread.currentThread().isInterrupted());
            } catch (Exception ignored) { }
        });
        search.start();
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            search.interrupt();
            search.join(1000);
            assertFalse(search.isAlive());
            assertTrue(cancelled.get());
        } finally {
            release.countDown();
            search.interrupt();
            search.join(1000);
        }
    }

    private static String id(int number) {
        return String.format(java.util.Locale.ROOT, "00000000-0000-0000-0000-%012d", number);
    }

    private static JSONObject sepia(String title, String uuid) throws Exception {
        return new JSONObject().put("name", title).put("uuid", uuid).put("duration", 7644)
                .put("url", "https://films.example/videos/watch/" + uuid)
                .put("thumbnailUrl", "/thumb.jpg");
    }

    private static JSONObject peerSeek(String title, String uuid) throws Exception {
        return new JSONObject().put("title", title).put("durationSeconds", 7644)
                .put("videoUrl", "https://films.example/w/" + uuid)
                .put("thumbnailUrl", "https://films.example/thumb.jpg");
    }

    private static String response(boolean peerSeek, JSONObject... items) throws Exception {
        return new JSONObject().put(peerSeek ? "videos" : "data", new JSONArray(items)).toString();
    }

    private static String playable() {
        return "{\"duration\":7644,\"files\":[{\"fileUrl\":\"https://films.example/video.mp4\","
                + "\"width\":1280,\"height\":720,\"hasVideo\":true,\"hasAudio\":true}]}";
    }

    private static String query(String address) throws Exception {
        for (String parameter : URI.create(address).getRawQuery().split("&")) {
            if (parameter.startsWith("search=")) return URLDecoder.decode(parameter.substring(7), "UTF-8");
            if (parameter.startsWith("q=")) return URLDecoder.decode(parameter.substring(2), "UTF-8");
        }
        return "";
    }
}
