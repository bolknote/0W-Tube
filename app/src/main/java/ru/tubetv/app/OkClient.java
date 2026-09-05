package ru.tubetv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class OkClient {
    private static final int LIMIT = 12;
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_SEARCH_BYTES = 2 * 1024 * 1024;
    private static final int MAX_PLAYER_BYTES = 1024 * 1024;
    static final String REFERER = "https://ok.ru/";
    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36";

    static List<VideoItem> search(String query, int minWidth) throws Exception {
        String address = "https://ok.ru/video/search"
                + "?st.cmd=anonymVideo"
                + "&st.ft=search"
                + "&st.gsq=" + encode(query)
                + "&st.m=SEARCH";
        List<VideoItem> result = parseSearchResults(get(address, REFERER, MAX_SEARCH_BYTES));
        if (minWidth <= 0 || result.isEmpty()) return result;
        List<VideoItem> filtered = new ArrayList<>();
        for (VideoItem item : result) {
            if (item.maxWidth >= minWidth) filtered.add(item);
        }
        return filtered;
    }

    static List<VideoItem> parseSearchResults(String html) throws Exception {
        int component = html.indexOf("<video-search-result");
        int tagEnd = component < 0 ? -1 : html.indexOf('>', component);
        if (component < 0 || tagEnd < 0) {
            throw new Exception("OK не отдал результаты поиска");
        }
        String encoded = attribute(html.substring(component, tagEnd + 1), "data-props");
        if (encoded == null || encoded.isEmpty()) {
            throw new Exception("OK не отдал данные результатов");
        }

        JSONObject props = new JSONObject(decodeHtml(encoded));
        JSONObject videos = props.optJSONObject("videos");
        JSONArray list = videos == null ? null : videos.optJSONArray("list");
        List<VideoItem> result = new ArrayList<>();
        if (list == null) return result;

        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < list.length() && result.size() < LIMIT; i++) {
            JSONObject card = list.optJSONObject(i);
            JSONObject movie = card == null ? null : card.optJSONObject("movie");
            if (movie == null || movie.optBoolean("blocked")) continue;
            String provider = movie.optString("provider");
            if (!isNativeProvider(provider)) continue;

            String id = movie.optString("id");
            if (!isVideoId(id) || !seen.add(id)) continue;
            String title = decodeHtml(card.optString("name", movie.optString("title")));
            if (title.isEmpty()) title = "Видео OK";
            String thumbnail = decodeHtml(card.optString("imageUrl"));
            if (thumbnail.isEmpty()) {
                JSONObject thumbnails = movie.optJSONObject("thumbnail");
                if (thumbnails != null) {
                    thumbnail = decodeHtml(thumbnails.optString("big",
                            thumbnails.optString("small")));
                }
            }
            long durationMs = Math.max(0L, movie.optLong("duration"));
            String page = "https://ok.ru/video/" + id;
            String embed = "https://ok.ru/videoembed/" + id;
            VideoItem item = new VideoItem("OK", title, "", thumbnail, embed, page, durationMs);
            int width = Math.max(0, movie.optInt("width"));
            int height = Math.max(0, movie.optInt("height"));
            result.add(width > 0 && height > 0 ? item.withQuality(width, height) : item);
        }
        return result;
    }

    static PlayerData loadPlayerData(String url) throws Exception {
        String id = findVideoId(url);
        Exception desktopError;
        try {
            String html = get("https://ok.ru/videoembed/" + id, REFERER, MAX_PLAYER_BYTES);
            return parseDesktopPlayer(id, html);
        } catch (Exception error) {
            BoundedHttpReader.checkInterrupted();
            desktopError = error;
        }

        try {
            String html = get("https://m.ok.ru/video/" + id, REFERER, MAX_PLAYER_BYTES);
            return parseMobilePlayer(id, html);
        } catch (Exception ignored) {
            throw desktopError;
        }
    }

    static String getCdnText(String address) throws Exception {
        return get(address, REFERER, MAX_PLAYER_BYTES);
    }

    static PlayerData parseDesktopPlayer(String id, String html) throws Exception {
        String error = textByClass(html, "vp_video_stub_txt");
        if (!error.isEmpty()) throw new Exception("Видео OK недоступно: " + error);
        if (html.contains(">Access to this video is restricted</div>")) {
            throw new Exception("Видео OK требует авторизацию");
        }

        JSONObject player = playerOptions(html, id);
        if (player.optBoolean("isExternalPlayer")) {
            String external = httpUrl(player.optString("url"));
            if (external != null) return new PlayerData(id, null, external, null);
        }

        JSONObject flashvars = player.optJSONObject("flashvars");
        if (flashvars == null) throw new Exception("OK не отдал параметры плеера");
        JSONObject metadata = null;
        Object inline = flashvars.opt("metadata");
        if (inline instanceof JSONObject) {
            metadata = (JSONObject) inline;
        } else if (inline != null) {
            String value = String.valueOf(inline);
            if (!value.isEmpty() && !"null".equals(value)) metadata = new JSONObject(value);
        }
        if (metadata == null) {
            String metadataUrl = flashvars.optString("metadataUrl");
            if (metadataUrl.isEmpty()) throw new Exception("OK не отдал метаданные ролика");
            // Python's urllib.parse.unquote, used by yt-dlp here, keeps literal '+'
            // characters intact. URLDecoder implements form decoding, so protect them.
            metadataUrl = httpUrl(URLDecoder.decode(
                    metadataUrl.replace("+", "%2B"), StandardCharsets.UTF_8.name()));
            if (metadataUrl == null) throw new Exception("OK отдал неверный URL метаданных");
            String location = flashvars.optString("location");
            String body = location.isEmpty() ? "" : "st.location=" + encode(location);
            metadata = new JSONObject(post(metadataUrl, body, REFERER, MAX_PLAYER_BYTES));
        }
        return new PlayerData(id, metadata, null, null);
    }

    static PlayerData parseMobilePlayer(String id, String html) throws Exception {
        String emptyMarker = "<div class=\"empty\">";
        int empty = html.indexOf(emptyMarker);
        if (empty >= 0) {
            int end = html.indexOf("</div>", empty + emptyMarker.length());
            String error = end < 0 ? "" : stripTags(
                    html.substring(empty + emptyMarker.length(), end)).trim();
            if (!error.isEmpty()) throw new Exception("Видео OK недоступно: " + decodeHtml(error));
        }

        String encoded = firstAttribute(html, "data-video");
        if (encoded == null || encoded.isEmpty()) {
            throw new Exception("OK не отдал мобильный поток");
        }
        JSONObject data = new JSONObject(decodeHtml(encoded));
        String stream = httpUrl(data.optString("videoSrc"));
        if (stream == null) throw new Exception("OK не отдал мобильный поток");
        try {
            stream = followHead(stream);
        } catch (Exception ignored) { }
        return new PlayerData(id, null, null, stream);
    }

    static boolean isOkUrl(String url) {
        if (url == null) return false;
        String value = url.toLowerCase(Locale.US);
        boolean domain = value.contains("://ok.ru/") || value.contains("://www.ok.ru/")
                || value.contains("://m.ok.ru/") || value.contains("://mobile.ok.ru/")
                || value.contains("://odnoklassniki.ru/")
                || value.contains("://www.odnoklassniki.ru/")
                || value.contains("://m.odnoklassniki.ru/")
                || value.contains("://mobile.odnoklassniki.ru/");
        if (!domain) return false;
        try {
            findVideoId(url);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    static String findVideoId(String url) throws Exception {
        if (url == null) throw new Exception("Не найден ID OK");
        String[] markers = {
                "/videoembed/", "/video/", "/web-api/video/moviePlayer/", "/live/", "st.mvId="
        };
        for (String marker : markers) {
            int start = url.indexOf(marker);
            if (start < 0) continue;
            start += marker.length();
            int end = start;
            boolean digit = false;
            while (end < url.length()) {
                char value = url.charAt(end);
                if (value >= '0' && value <= '9') {
                    digit = true;
                    end++;
                } else if (value == '-') {
                    end++;
                } else {
                    break;
                }
            }
            String candidate = url.substring(start, end);
            if (digit && isVideoId(candidate)) return candidate;
        }
        throw new Exception("Не найден ID OK");
    }

    static String decodeHtml(String value) {
        if (value == null || value.indexOf('&') < 0) return value == null ? "" : value;
        StringBuilder result = new StringBuilder(value.length());
        int cursor = 0;
        while (cursor < value.length()) {
            char current = value.charAt(cursor);
            if (current != '&') {
                result.append(current);
                cursor++;
                continue;
            }
            int semicolon = value.indexOf(';', cursor + 1);
            if (semicolon < 0 || semicolon - cursor > 12) {
                result.append(current);
                cursor++;
                continue;
            }
            String entity = value.substring(cursor + 1, semicolon);
            String decoded = decodeEntity(entity);
            if (decoded == null) {
                result.append(current);
                cursor++;
            } else {
                result.append(decoded);
                cursor = semicolon + 1;
            }
        }
        return result.toString();
    }

    private static JSONObject playerOptions(String html, String id) throws Exception {
        int cursor = 0;
        while (cursor < html.length()) {
            int at = html.indexOf("data-options=", cursor);
            if (at < 0) break;
            int valueStart = at + "data-options=".length();
            if (valueStart >= html.length()) break;
            char quote = html.charAt(valueStart);
            if (quote != '"' && quote != '\'') {
                cursor = valueStart + 1;
                continue;
            }
            int end = html.indexOf(quote, valueStart + 1);
            if (end < 0) break;
            String decoded = decodeHtml(html.substring(valueStart + 1, end));
            if (decoded.contains(id)) {
                return new JSONObject(decoded);
            }
            cursor = end + 1;
        }
        throw new Exception("OK не отдал данные плеера");
    }

    private static boolean isNativeProvider(String provider) {
        if (provider == null || provider.isEmpty()) return true;
        String normalized = provider.replace("_", "").toUpperCase(Locale.US);
        return "UPLOADEDODKL".equals(normalized) || "LIVETVAPP".equals(normalized);
    }

    private static boolean isVideoId(String value) {
        if (value == null || value.isEmpty()) return false;
        boolean digit = false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current >= '0' && current <= '9') digit = true;
            else if (current != '-') return false;
        }
        return digit;
    }

    private static String firstAttribute(String html, String name) {
        int marker = html.indexOf(name + "=");
        if (marker < 0) return null;
        int tagStart = html.lastIndexOf('<', marker);
        int tagEnd = html.indexOf('>', marker);
        if (tagStart < 0 || tagEnd < 0) return null;
        return attribute(html.substring(tagStart, tagEnd + 1), name);
    }

    private static String attribute(String tag, String name) {
        String marker = name + "=";
        int at = tag.indexOf(marker);
        if (at < 0) return null;
        int start = at + marker.length();
        if (start >= tag.length()) return null;
        char quote = tag.charAt(start);
        if (quote != '"' && quote != '\'') return null;
        int end = tag.indexOf(quote, start + 1);
        return end < 0 ? null : tag.substring(start + 1, end);
    }

    private static String textByClass(String html, String className) {
        int classAt = html.indexOf(className);
        if (classAt < 0) return "";
        int start = html.indexOf('>', classAt);
        int end = start < 0 ? -1 : html.indexOf("</", start + 1);
        if (start < 0 || end < 0) return "";
        return decodeHtml(stripTags(html.substring(start + 1, end))).trim();
    }

    private static String stripTags(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean tag = false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '<') tag = true;
            else if (current == '>') tag = false;
            else if (!tag) result.append(current);
        }
        return result.toString();
    }

    private static String decodeEntity(String entity) {
        switch (entity) {
            case "quot": return "\"";
            case "amp": return "&";
            case "apos":
            case "#39": return "'";
            case "lt": return "<";
            case "gt": return ">";
            case "nbsp": return "\u00a0";
            default:
                if (!entity.startsWith("#")) return null;
                try {
                    int radix = entity.length() > 2
                            && (entity.charAt(1) == 'x' || entity.charAt(1) == 'X') ? 16 : 10;
                    int start = radix == 16 ? 2 : 1;
                    int codePoint = Integer.parseInt(entity.substring(start), radix);
                    return new String(Character.toChars(codePoint));
                } catch (Exception ignored) {
                    return null;
                }
        }
    }

    private static String httpUrl(String value) {
        if (value == null || value.isEmpty()) return null;
        if (value.startsWith("//")) return "https:" + value;
        return value.startsWith("http://") || value.startsWith("https://") ? value : null;
    }

    private static String followHead(String address) throws Exception {
        HttpURLConnection connection = configure(
                (HttpURLConnection) new URL(address).openConnection(), REFERER);
        connection.setRequestMethod("HEAD");
        connection.setInstanceFollowRedirects(true);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 400) throw new Exception("OK CDN: HTTP " + code);
            return connection.getURL().toString();
        } finally {
            connection.disconnect();
        }
    }

    private static String get(String address, String referer, int limit) throws Exception {
        BoundedHttpReader.checkInterrupted();
        HttpURLConnection connection = configure(
                (HttpURLConnection) new URL(address).openConnection(), referer);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("OK: HTTP " + code);
            return read(connection, limit);
        } finally {
            connection.disconnect();
        }
    }

    private static String post(String address, String body, String referer, int limit) throws Exception {
        BoundedHttpReader.checkInterrupted();
        HttpURLConnection connection = configure(
                (HttpURLConnection) new URL(address).openConnection(), referer);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type",
                "application/x-www-form-urlencoded; charset=UTF-8");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try {
            OutputStream output = connection.getOutputStream();
            output.write(bytes);
            output.close();
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("OK: HTTP " + code);
            return read(connection, limit);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection configure(HttpURLConnection connection, String referer) {
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "text/html,application/json,*/*");
        connection.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Cookie", "");
        if (referer != null && !referer.isEmpty()) connection.setRequestProperty("Referer", referer);
        return connection;
    }

    private static String read(HttpURLConnection connection, int limit) throws Exception {
        return BoundedHttpReader.readText(connection.getInputStream(), limit);
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
    }

    static final class PlayerData {
        final String id;
        final JSONObject metadata;
        final String externalUrl;
        final String mobileUrl;

        PlayerData(String id, JSONObject metadata, String externalUrl, String mobileUrl) {
            this.id = id;
            this.metadata = metadata;
            this.externalUrl = externalUrl;
            this.mobileUrl = mobileUrl;
        }
    }

    private OkClient() { }
}
