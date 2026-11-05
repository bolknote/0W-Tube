package ru.tubetv.app;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class VkWebClient {
    private static final int LIMIT = 12;
    private static final int MAX_PAGES = 4;
    private static final int PAGE_SIZE = 30;
    private static final int TIMEOUT_MS = 10_000;
    private static final String CLIENT_ID = "52461373";
    private static final String API_VERSION = "5.282";
    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 Chrome/150.0.0.0 Safari/537.36";
    private static String anonymousToken;
    private static long tokenExpiresAt;

    static JSONObject getVideoById(String videoId) throws Exception {
        return getVideoById(videoId, true);
    }

    private static JSONObject getVideoById(String videoId, boolean retryToken) throws Exception {
        String address = "https://api.vk.com/method/video.getByIds"
                + "?v=" + API_VERSION
                + "&client_id=" + CLIENT_ID;
        String body = "access_token=" + Uri.encode(getAnonymousToken())
                + "&videos=" + Uri.encode(videoId)
                + "&video_fields=" + Uri.encode("files");
        JSONObject root = postJson(address, body);
        JSONObject error = root.optJSONObject("error");
        if (error != null) {
            if (retryToken && isAuthorizationError(root)) {
                clearAnonymousToken();
                return getVideoById(videoId, false);
            }
            throw new Exception("VK Video: "
                    + error.optString("error_msg", "ошибка получения потока"));
        }
        JSONObject response = root.optJSONObject("response");
        JSONArray items = response == null ? null : response.optJSONArray("items");
        JSONObject video = items == null ? null : items.optJSONObject(0);
        if (video == null) throw new Exception("VK Video не отдал данные ролика");
        return video;
    }

    interface PageListener {
        boolean onPage(List<VideoItem> items);
    }

    List<VideoItem> search(String query, int minWidth, int thumbnailWidth) throws Exception {
        List<VideoItem> result = new ArrayList<>();
        searchPages(query, minWidth, thumbnailWidth, page -> {
            result.addAll(page);
            return true;
        });
        if (minWidth <= 0 || result.isEmpty()) return result;
        return SearchClient.filterByQuality(result, minWidth);
    }

    int searchPages(String query, int minWidth, int thumbnailWidth,
                    PageListener listener) throws Exception {
        return newSearch(query, thumbnailWidth).searchPages(minWidth, listener);
    }

    SearchSession newSearch(String query, int thumbnailWidth) {
        return new SearchSession(query, thumbnailWidth, (sectionId, nextFrom) -> {
            String address;
            String body = null;
            if (nextFrom == null) {
                address = "https://api.vkvideo.ru/method/catalog.getVideoSearchWeb2"
                        + "?v=" + API_VERSION + "&client_id=" + CLIENT_ID
                        + "&count=" + PAGE_SIZE + "&q=" + Uri.encode(query)
                        + "&content_type=video";
            } else {
                address = "https://api.vkvideo.ru/method/catalog.getSection"
                        + "?v=" + API_VERSION + "&client_id=" + CLIENT_ID;
                body = "section_id=" + Uri.encode(sectionId)
                        + "&start_from=" + Uri.encode(nextFrom);
            }
            return response(authorizedJson(address, body));
        });
    }

    interface PageLoader {
        JSONObject load(String sectionId, String nextFrom) throws Exception;
    }

    /** One query's cursor, buffered cards and shared request budget; callers serialize loading. */
    static final class SearchSession {
        private final int thumbnailWidth;
        private final PageLoader loader;
        private final SearchTitleFilter titleFilter;
        private final List<VideoItem> candidates = new ArrayList<>();
        private final Set<String> seen = new LinkedHashSet<>();
        private final Set<String> requestedCursors = new LinkedHashSet<>();
        private PageCursor next;
        private int requests;
        private int delivered;
        private boolean exhausted;

        SearchSession(int thumbnailWidth, PageLoader loader) {
            this("", thumbnailWidth, loader);
        }

        SearchSession(String query, int thumbnailWidth, PageLoader loader) {
            this.thumbnailWidth = thumbnailWidth;
            this.loader = loader;
            this.titleFilter = new SearchTitleFilter(query);
        }

        boolean needsMore(int minWidth) {
            return deliveryEnd(minWidth) > delivered
                    || (!hasEnough(minWidth) && !exhausted && requests < MAX_PAGES);
        }

        int requestCount() { return requests; }

        int searchPages(int minWidth, PageListener listener) throws Exception {
            int total = 0;
            while (!Thread.currentThread().isInterrupted() && needsMore(minWidth)) {
                int end = deliveryEnd(minWidth);
                if (end > delivered) {
                    List<VideoItem> page = new ArrayList<>(candidates.subList(delivered, end));
                    delivered = end;
                    total += page.size();
                    if (!listener.onPage(page)) break;
                    continue;
                }

                String cursorKey = next == null ? "" : next.sectionId + "\n" + next.nextFrom;
                if (!requestedCursors.add(cursorKey)) {
                    exhausted = true;
                    break;
                }
                requests++;
                try {
                    JSONObject page = loader.load(next == null ? null : next.sectionId,
                            next == null ? null : next.nextFrom);
                    // Keep the entire bounded page: a stricter filter may need cards
                    // after the ordinary 12-result cutoff without fetching it again.
                    for (VideoItem item : parsePage(page, thumbnailWidth, seen, PAGE_SIZE)) {
                        if (titleFilter.accepts(item.title)) candidates.add(item);
                    }
                    next = pageCursor(page);
                    if (next == null) exhausted = true;
                } catch (Exception error) {
                    exhausted = true;
                    throw error;
                }
            }
            return total;
        }

        private int deliveryEnd(int minWidth) {
            int end = Math.min(LIMIT, candidates.size());
            if (minWidth > 0) {
                end = candidates.size();
                int matching = 0;
                for (int i = 0; i < candidates.size(); i++) {
                    if (candidates.get(i).maxWidth >= minWidth && ++matching == LIMIT) {
                        end = i + 1;
                        break;
                    }
                }
            }
            return Math.max(delivered, end);
        }

        private boolean hasEnough(int minWidth) {
            if (minWidth <= 0) return candidates.size() >= LIMIT;
            int matching = 0;
            for (VideoItem item : candidates) {
                if (item.maxWidth >= minWidth && ++matching >= LIMIT) return true;
            }
            return false;
        }
    }

    private static JSONObject authorizedJson(String address, String body) throws Exception {
        JSONObject root = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = "access_token=" + Uri.encode(getAnonymousToken());
            root = body == null
                    ? getJson(address + "&" + token)
                    : postJson(address, body + "&" + token);
            if (!isAuthorizationError(root) || attempt == 1) return root;
            clearAnonymousToken();
        }
        return root;
    }

    static boolean isAuthorizationError(JSONObject root) {
        JSONObject error = root == null ? null : root.optJSONObject("error");
        if (error == null) return false;
        if (error.optInt("error_code") == 5) return true;
        return error.optString("error_msg").toLowerCase(Locale.ROOT)
                .contains("authorization failed");
    }

    private static JSONObject response(JSONObject root) throws Exception {
        JSONObject error = root.optJSONObject("error");
        if (error != null) {
            throw new Exception("VK Video: "
                    + error.optString("error_msg", "ошибка поиска"));
        }
        JSONObject response = root.optJSONObject("response");
        if (response == null) throw new Exception("VK Video не отдал результаты");
        return response;
    }

    private static List<VideoItem> parsePage(JSONObject response, int thumbnailWidth,
                                             Set<String> seen, int limit) {
        LinkedHashMap<String, JSONObject> videos = new LinkedHashMap<>();
        JSONArray catalogVideos = response.optJSONArray("catalog_videos");
        if (catalogVideos != null) {
            for (int i = 0; i < catalogVideos.length(); i++) {
                JSONObject wrapper = catalogVideos.optJSONObject(i);
                JSONObject video = wrapper == null ? null : wrapper.optJSONObject("video");
                if (video != null) videos.put(key(video), video);
            }
        }

        Set<String> orderedIds = new LinkedHashSet<>();
        JSONObject catalog = response.optJSONObject("catalog");
        collectOrderedIds(catalog == null ? null : catalog.optJSONArray("sections"), orderedIds);
        collectOrderedIds(response.optJSONObject("section"), orderedIds);
        orderedIds.addAll(videos.keySet());

        List<VideoItem> result = new ArrayList<>();
        for (String id : orderedIds) {
            JSONObject video = videos.get(id);
            if (video == null || !seen.add(id)) continue;
            String page = "https://vkvideo.ru/video" + VkVideoId.fromVideo(video);
            VideoItem item = new VideoItem("VK VIDEO", video.optString("title", "Видео VK"),
                    "", bestImage(video.optJSONArray("image"), thumbnailWidth), page, page,
                    video.optLong("duration") * 1000L);
            int[] dimensions = maxAvailableDimensions(video);
            result.add(item.withQuality(dimensions[0], dimensions[1]));
            if (result.size() >= limit) break;
        }
        return result;
    }

    private static PageCursor pageCursor(JSONObject response) {
        JSONObject section = response.optJSONObject("section");
        if (section == null) {
            JSONObject catalog = response.optJSONObject("catalog");
            JSONArray sections = catalog == null ? null : catalog.optJSONArray("sections");
            section = sections == null ? null : sections.optJSONObject(0);
        }
        if (section == null) return null;
        String sectionId = section.optString("id");
        String nextFrom = section.optString("next_from");
        return sectionId.isEmpty() || nextFrom.isEmpty()
                ? null : new PageCursor(sectionId, nextFrom);
    }

    private static synchronized String getAnonymousToken() throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        if (anonymousToken != null && now + 60 < tokenExpiresAt) return anonymousToken;
        JSONObject root = postJson("https://login.vk.com/?act=get_anonym_token", "client_id=" + CLIENT_ID);
        if (!"okay".equals(root.optString("type"))) throw new Exception("VK Video не выдал анонимную сессию");
        JSONObject data = root.optJSONObject("data");
        anonymousToken = data == null ? null : data.optString("access_token", null);
        tokenExpiresAt = data == null ? 0 : data.optLong("expired_at");
        if (anonymousToken == null || anonymousToken.isEmpty()) throw new Exception("VK Video не выдал анонимную сессию");
        return anonymousToken;
    }

    private static synchronized void clearAnonymousToken() {
        anonymousToken = null;
        tokenExpiresAt = 0;
    }

    private static void collectOrderedIds(JSONArray sections, Set<String> result) {
        if (sections == null) return;
        for (int i = 0; i < sections.length(); i++) {
            collectOrderedIds(sections.optJSONObject(i), result);
        }
    }

    private static void collectOrderedIds(JSONObject section, Set<String> result) {
        JSONArray blocks = section == null ? null : section.optJSONArray("blocks");
        if (blocks == null) return;
        for (int j = 0; j < blocks.length(); j++) {
            JSONObject block = blocks.optJSONObject(j);
            JSONArray ids = block == null ? null : block.optJSONArray("videos_ids");
            if (ids == null) continue;
            for (int k = 0; k < ids.length(); k++) {
                result.add(ids.optString(k));
            }
        }
    }

    private static String key(JSONObject video) {
        return video.optLong("owner_id") + "_" + video.optLong("id");
    }

    private static int[] maxAvailableDimensions(JSONObject video) {
        int bestHeight = 0;
        JSONObject files = video.optJSONObject("files");
        if (files != null) {
            java.util.Iterator<String> keys = files.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String url = files.optString(key);
                if (!key.startsWith("mp4_")
                        || !(url.startsWith("http") || url.startsWith("//"))) continue;
                try {
                    bestHeight = Math.max(bestHeight, Integer.parseInt(key.substring(4)));
                } catch (NumberFormatException ignored) { }
            }
        }
        if (bestHeight > 0) return new int[]{standardWidth(bestHeight), bestHeight};
        return new int[]{Math.max(0, video.optInt("width")),
                Math.max(0, video.optInt("height"))};
    }

    private static int standardWidth(int height) {
        switch (height) {
            case 144: return 256;
            case 240: return 426;
            case 360: return 640;
            case 480: return 854;
            case 720: return 1280;
            case 1080: return 1920;
            case 1440: return 2560;
            case 2160: return 3840;
            default: return Math.round(height * 16f / 9f);
        }
    }

    private static String bestImage(JSONArray images, int targetWidth) {
        if (images == null) return "";
        String smallestSuitable = "";
        int smallestSuitableWidth = Integer.MAX_VALUE;
        String largestFallback = "";
        int largestFallbackWidth = 0;
        for (int i = 0; i < images.length(); i++) {
            JSONObject image = images.optJSONObject(i);
            if (image == null) continue;
            int width = image.optInt("width");
            String url = image.optString("url");
            if (url.isEmpty() || width <= 0) continue;
            if (width > largestFallbackWidth) {
                largestFallbackWidth = width;
                largestFallback = url;
            }
            if (width >= targetWidth && width < smallestSuitableWidth) {
                smallestSuitableWidth = width;
                smallestSuitable = url;
            }
        }
        return smallestSuitable.isEmpty() ? largestFallback : smallestSuitable;
    }

    private static JSONObject getJson(String address) throws Exception {
        BoundedHttpReader.checkInterrupted();
        HttpURLConnection connection = configure((HttpURLConnection) new URL(address).openConnection());
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("VK Video: HTTP " + code);
            return new JSONObject(read(connection));
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject postJson(String address, String body) throws Exception {
        BoundedHttpReader.checkInterrupted();
        HttpURLConnection connection = configure((HttpURLConnection) new URL(address).openConnection());
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        connection.setRequestProperty("Origin", "https://vkvideo.ru");
        connection.setRequestProperty("Referer", "https://vkvideo.ru/");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try {
            OutputStream output = connection.getOutputStream();
            output.write(bytes);
            output.close();
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("VK Video: HTTP " + code);
            return new JSONObject(read(connection));
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection configure(HttpURLConnection connection) {
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json,*/*");
        connection.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        return connection;
    }

    private static String read(HttpURLConnection connection) throws Exception {
        return BoundedHttpReader.readText(connection.getInputStream(), BoundedHttpReader.JSON_LIMIT);
    }

    private static final class PageCursor {
        final String sectionId;
        final String nextFrom;

        PageCursor(String sectionId, String nextFrom) {
            this.sectionId = sectionId;
            this.nextFrom = nextFrom;
        }
    }

}
