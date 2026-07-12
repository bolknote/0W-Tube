package ru.tubetv.app;

import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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
    private static final int TIMEOUT_MS = 9_000;
    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36";
    private static final Map<String, String> COOKIES = new LinkedHashMap<>();

    List<VideoItem> search(String query, int minWidth) throws Exception {
        String address = "https://dzen.ru/search?query=" + Uri.encode(query) + "&type_filter=video";
        String html = getPage(address);
        List<VideoItem> result = parseCards(html);
        if (minWidth <= 0 || result.isEmpty()) return result;
        return SearchClient.filterByQuality(result, minWidth, 2);
    }

    static String getPage(String address) throws Exception {
        // A fresh anonymous Dzen session normally answers with two SSO redirects. The
        // redirects only set anonymous cookies; repeating the original URL is enough
        // and avoids loading either SSO web page or executing JavaScript.
        for (int attempt = 0; attempt < 4; attempt++) {
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
                InputStream input = connection.getInputStream();
                ByteArrayOutputStream output = new ByteArrayOutputStream(256 * 1024);
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    output.write(buffer, 0, read);
                }
                input.close();
                String body = output.toString(StandardCharsets.UTF_8.name());
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
