package ru.tubetv.app;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DzenClient {
    private static final int LIMIT = 12;
    private static final int MAX_PAGES = 3;
    private static final int TIMEOUT_MS = 9_000;
    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36";
    private static final Map<String, String> COOKIES = new LinkedHashMap<>();

    interface PageListener {
        boolean onPage(List<VideoItem> items);
    }

    List<VideoItem> search(String query, int minWidth) throws Exception {
        List<VideoItem> result = new ArrayList<>();
        searchPages(query, page -> {
            result.addAll(page);
            return true;
        });
        if (minWidth <= 0 || result.isEmpty()) return result;

        List<VideoItem> filtered = new ArrayList<>();
        List<VideoItem> unknown = new ArrayList<>();
        for (VideoItem item : result) {
            if (item.maxWidth == 0) unknown.add(item);
            else if (item.maxWidth >= minWidth) filtered.add(item);
        }
        filtered.addAll(SearchClient.filterByQuality(unknown, minWidth, 2));
        return filtered;
    }

    int searchPages(String query, PageListener listener) throws Exception {
        try {
            int count = searchJsonPages(query, listener);
            if (count > 0) return count;
        } catch (Exception jsonError) {
            if (Thread.currentThread().isInterrupted()) throw jsonError;
        }
        String address = "https://dzen.ru/search?query=" + Uri.encode(query)
                + "&type_filter=video";
        List<VideoItem> fallback = parseCards(getPage(address));
        if (!fallback.isEmpty()) listener.onPage(fallback);
        return fallback.size();
    }

    private int searchJsonPages(String query, PageListener listener) throws Exception {
        String address = "https://dzen.ru/api/web/v1/zen-search"
                + "?country_code=ru"
                + "&forced_request_type=long_video_search"
                + "&query=" + Uri.encode(query)
                + "&clid=1400"
                + "&type_filter=video"
                + "&lang=ru";
        Set<String> seen = new LinkedHashSet<>();
        int total = 0;
        for (int pageNumber = 0; pageNumber < MAX_PAGES && total < LIMIT; pageNumber++) {
            JSONObject response;
            try {
                response = getJsonPage(address);
            } catch (Exception error) {
                if (total > 0) return total;
                throw error;
            }
            JSONObject feed = response.optJSONObject("feedData");
            if (feed == null) feed = response;
            List<VideoItem> page = parseJsonItems(feed.optJSONArray("items"),
                    seen, LIMIT - total);
            total += page.size();
            if (!page.isEmpty() && !listener.onPage(page)) break;

            JSONObject more = feed.optJSONObject("more");
            String next = more == null ? "" : more.optString("link");
            if (next.isEmpty() || next.equals(address)) break;
            address = next;
        }
        return total;
    }

    private static List<VideoItem> parseJsonItems(JSONArray items, Set<String> seen, int limit) {
        List<VideoItem> result = new ArrayList<>();
        if (items == null || limit <= 0) return result;
        for (int i = 0; i < items.length() && result.size() < limit; i++) {
            JSONObject item = items.optJSONObject(i);
            JSONObject video = item == null ? null : item.optJSONObject("video");
            String link = item == null ? "" : item.optString("link");
            String id = videoId(link);
            if (video == null || id.isEmpty() || !seen.add(id)) continue;

            String page = "https://dzen.ru/video/watch/" + id;
            String thumbnail = thumbnail(item.optJSONObject("image"));
            int[] dimensions = dimensions(video);
            StreamResolver.cacheDzen(id, video, dimensions[0], dimensions[1]);
            VideoItem parsed = new VideoItem("ДЗЕН",
                    item.optString("title", "Видео Дзен"), "",
                    thumbnail, page, page, video.optLong("duration") * 1000L);
            result.add(parsed.withQuality(dimensions[0], dimensions[1]));
        }
        return result;
    }

    private static String videoId(String link) {
        String marker = "/video/watch/";
        int start = link.indexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        int end = start;
        while (end < link.length() && isIdChar(link.charAt(end))) end++;
        return end == start ? "" : link.substring(start, end);
    }

    private static String thumbnail(JSONObject image) {
        if (image == null) return "";
        String direct = image.optString("url");
        if (!direct.isEmpty()) return direct;
        String template = image.optString("urlTemplate");
        String namespace = image.optString("namespace");
        if (template.isEmpty() || namespace.isEmpty()) return "";
        String size = image.optString("sizeName", "scale_1200");
        return template.replace("{namespace}", namespace).replace("{size}", size);
    }

    private static int[] dimensions(JSONObject video) {
        int bestWidth = video.optInt("width");
        int bestHeight = video.optInt("height");
        JSONArray resolutions = video.optJSONArray("resolutions");
        if (resolutions != null) {
            for (int i = 0; i < resolutions.length(); i++) {
                JSONObject resolution = resolutions.optJSONObject(i);
                if (resolution == null) continue;
                int width = resolution.optInt("width");
                int height = resolution.optInt("height");
                if (width > bestWidth) {
                    bestWidth = width;
                    bestHeight = height;
                }
            }
        }
        return new int[]{bestWidth, bestHeight};
    }

    private static JSONObject getJsonPage(String address) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            BoundedHttpReader.checkInterrupted();
            HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            String cookie = cookieHeader();
            if (!cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
            try {
                int code = connection.getResponseCode();
                rememberCookies(connection);
                if (code >= 300 && code < 400) continue;
                if (code < 200 || code >= 300) throw new Exception("Дзен: HTTP " + code);
                return new JSONObject(BoundedHttpReader.readText(connection.getInputStream(),
                        BoundedHttpReader.JSON_LIMIT));
            } finally {
                connection.disconnect();
            }
        }
        throw new Exception("Дзен не создал анонимную сессию");
    }

    static String getPage(String address) throws Exception {
        // A fresh anonymous Dzen session normally answers with two SSO redirects. The
        // redirects only set anonymous cookies; repeating the original URL is enough
        // and avoids loading either SSO web page or executing JavaScript.
        for (int attempt = 0; attempt < 4; attempt++) {
            BoundedHttpReader.checkInterrupted();
            HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            String cookie = cookieHeader();
            if (!cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
            try {
                int code = connection.getResponseCode();
                rememberCookies(connection);
                if (code >= 300 && code < 400) continue;
                if (code < 200 || code >= 300) throw new Exception("Дзен: HTTP " + code);
                String body = BoundedHttpReader.readText(connection.getInputStream(), BoundedHttpReader.PAGE_LIMIT);
                if (body.contains("data-card-type=\"card-video\"")
                        || body.contains("\"videoMetaResponse\"")) return body;
                // Cookies can expire between requests; retry the original page once.
                if (!body.contains("sso.dzen.ru") && !body.contains("sso.passport.yandex.ru")) return body;
            } finally {
                connection.disconnect();
            }
        }
        throw new Exception("Дзен не создал анонимную сессию");
    }

    private static List<VideoItem> parseCards(String html) {
        List<VideoItem> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String marker = "https://dzen.ru/video/watch/";
        int cursor = 0;
        while (result.size() < LIMIT) {
            int link = html.indexOf(marker, cursor);
            if (link < 0) break;
            int idStart = link + marker.length();
            int idEnd = idStart;
            while (idEnd < html.length() && isIdChar(html.charAt(idEnd))) idEnd++;
            String id = html.substring(idStart, idEnd);
            cursor = idEnd;
            if (id.isEmpty() || !seen.add(id)) continue;

            int articleStart = html.lastIndexOf("<article", link);
            int articleEnd = html.indexOf("</article>", link);
            if (articleStart < 0 || articleEnd < 0 || articleEnd - articleStart > 80_000) continue;
            String card = html.substring(articleStart, articleEnd);
            String title = textAfter(card, "data-testid=\"card-part-title\">");
            if (title.isEmpty()) title = attributeNear(card, "floor-card-video-wrapper-link", "aria-label");
            if (title.isEmpty()) title = "Видео Дзен";
            String durationText = textAfter(card, "aria-label=\"Общая длительность видео\">");
            long durationMs = parseDuration(durationText) * 1000L;
            String thumbnail = between(card, "background-image:url(", ")");
            String page = marker + id;
            result.add(new VideoItem("ДЗЕН", decode(title), "",
                    decode(thumbnail), page, page, durationMs));
        }
        return result;
    }

    private static boolean isIdChar(char value) {
        return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9' || value == '-' || value == '_';
    }

    private static String textAfter(String value, String marker) {
        int start = value.indexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        int end = value.indexOf('<', start);
        return end < 0 ? "" : value.substring(start, end).trim();
    }

    private static String attributeNear(String value, String marker, String attribute) {
        int markerAt = value.indexOf(marker);
        if (markerAt < 0) return "";
        int tagStart = value.lastIndexOf('<', markerAt);
        int tagEnd = value.indexOf('>', markerAt);
        if (tagStart < 0 || tagEnd < 0) return "";
        return between(value.substring(tagStart, tagEnd), attribute + "=\"", "\"");
    }

    private static String between(String value, String before, String after) {
        int start = value.indexOf(before);
        if (start < 0) return "";
        start += before.length();
        int end = value.indexOf(after, start);
        return end < 0 ? "" : value.substring(start, end);
    }

    private static int parseDuration(String value) {
        String[] parts = value.split(":");
        int seconds = 0;
        try {
            for (String part : parts) seconds = seconds * 60 + Integer.parseInt(part.trim());
            return seconds;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String decode(String value) {
        return value.replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
    }

    private static String cookieHeader() {
        synchronized (COOKIES) {
            StringBuilder result = new StringBuilder();
            for (Map.Entry<String, String> entry : COOKIES.entrySet()) {
                if (result.length() > 0) result.append("; ");
                result.append(entry.getKey()).append('=').append(entry.getValue());
            }
            return result.toString();
        }
    }

    private static void rememberCookies(HttpURLConnection connection) {
        Map<String, List<String>> headers = connection.getHeaderFields();
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            if (header.getKey() == null || !"set-cookie".equalsIgnoreCase(header.getKey())) continue;
            for (String value : header.getValue()) {
                int semicolon = value.indexOf(';');
                String pair = semicolon < 0 ? value : value.substring(0, semicolon);
                int equals = pair.indexOf('=');
                if (equals > 0) {
                    synchronized (COOKIES) {
                        COOKIES.put(pair.substring(0, equals), pair.substring(equals + 1));
                    }
                }
            }
        }
    }
}
