package ru.tubetv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

final class StreamResolver {
    private static final long CACHE_MS = 10 * 60 * 1000L;
    private static final ConcurrentHashMap<String, PlaybackInfo> CACHE = new ConcurrentHashMap<>();

    String resolve(String resolverUrl) throws Exception {
        return inspect(resolverUrl).streamUrl;
    }

    PlaybackInfo inspect(String url) throws Exception {
        if (url != null && (url.contains("dzen.ru/video/") || url.contains("zen.yandex.ru/video/"))) {
            return inspectDzen(url);
        }
        if (url != null && (url.contains("vkvideo.ru/") || url.contains("vk.com/video"))) {
            return inspectVk(url);
        }
        return inspectRutube(url);
    }

    private PlaybackInfo inspectDzen(String url) throws Exception {
        String id = findDzenId(url);
        String cacheKey = "dzen:" + id;
        PlaybackInfo cached = CACHE.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.loadedAt < CACHE_MS) return cached;

        String pageUrl = "https://dzen.ru/video/watch/" + id;
        String page = DzenClient.getPage(pageUrl);
        int metadata = page.indexOf("\"videoMetaResponse\"");
        int params = metadata < 0 ? -1 : page.lastIndexOf("var _params", metadata);
        int objectStart = params < 0 ? -1 : page.indexOf('{', params);
        if (objectStart < 0) throw new Exception("Дзен не отдал данные ролика");
        JSONObject root = new JSONObject(jsonObjectAt(page, objectStart));
        JSONObject ssr = root.optJSONObject("ssrData");
        JSONObject meta = ssr == null ? null : ssr.optJSONObject("videoMetaResponse");
        JSONObject video = meta == null ? null : meta.optJSONObject("video");
        if (video == null) throw new Exception("Дзен не отдал публичный поток");

        String hls = null;
        String fallback = null;
        String direct = httpUrl(video.optString("id"));
        if (isHls(direct)) hls = direct;
        else if (direct != null) fallback = direct;
        JSONArray streams = video.optJSONArray("streams");
        if (streams != null) {
            for (int i = 0; i < streams.length(); i++) {
                String candidate = httpUrl(streams.optString(i));
                if (candidate == null) continue;
                if (hls == null && isHls(candidate)) hls = candidate;
                if (fallback == null && candidate.contains("ct=0")) fallback = candidate;
            }
        }
        JSONArray oneVideo = video.optJSONArray("oneVideoStreams");
        if (oneVideo != null) {
            for (int i = 0; i < oneVideo.length(); i++) {
                JSONObject item = oneVideo.optJSONObject(i);
                String candidate = item == null ? null : httpUrl(item.optString("url"));
                if (candidate == null) continue;
                if (hls == null && ("hls".equals(item.optString("type")) || isHls(candidate))) hls = candidate;
                if (fallback == null && "fullhd".equals(item.optString("type"))) fallback = candidate;
            }
        }
        String stream = hls != null ? hls : fallback;
        if (stream == null) throw new Exception("Нет совместимого потока Дзен");

        int maxWidth = 0;
        int maxHeight = 0;
        if (hls != null) {
            try {
                int[] dimensions = findMaxDimensions(get(hls, pageUrl));
                maxWidth = dimensions[0];
                maxHeight = dimensions[1];
            } catch (Exception ignored) { }
        }
        if (maxWidth == 0 && fallback != null && fallback.contains("type=5")) {
            maxWidth = 1920;
            maxHeight = 1080;
        }
        PlaybackInfo result = new PlaybackInfo(stream, maxWidth, maxHeight);
        CACHE.put(cacheKey, result);
        return result;
    }

    private PlaybackInfo inspectRutube(String url) throws Exception {
        String id = findRutubeId(url);
        String cacheKey = "rutube:" + id;
        PlaybackInfo cached = CACHE.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.loadedAt < CACHE_MS) return cached;
        JSONObject options = new JSONObject(get("https://rutube.ru/api/play/options/" + id + "/?format=json",
                "https://rutube.ru/video/" + id + "/"));
        JSONObject balancer = options.optJSONObject("video_balancer");
        if (balancer == null) throw new Exception("RUTUBE не отдал поток");
        String fallback = null;
        String hls = null;
        int maxWidth = 0;
        int maxHeight = 0;
        for (Iterator<String> it = balancer.keys(); it.hasNext();) {
            String key = it.next();
            String value = balancer.optString(key);
            int[] dimensions = findMaxDimensions(value);
            if (dimensions[0] > maxWidth) {
                maxWidth = dimensions[0];
                maxHeight = dimensions[1];
            }
            if (hls == null && value.contains(".m3u8")) hls = value;
            if (fallback == null && value.startsWith("http")) fallback = value;
        }
        String stream = hls != null ? hls : fallback;
        if (stream == null) throw new Exception("Нет совместимого потока RUTUBE");
        PlaybackInfo result = new PlaybackInfo(stream, maxWidth, maxHeight);
        CACHE.put(cacheKey, result);
        return result;
    }

    private PlaybackInfo inspectVk(String url) throws Exception {
        String id = findVkId(url);
        String cacheKey = "vk:" + id;
        PlaybackInfo cached = CACHE.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.loadedAt < CACHE_MS) return cached;

        String body = "act=show&video=" + URLEncoder.encode(id, "UTF-8") + "&al=1";
        JSONObject root = new JSONObject(postVk(body));
        JSONArray envelope = root.optJSONArray("payload");
        if (envelope == null || envelope.length() < 2) throw new Exception("VK Video не отдал данные ролика");
        if ("3".equals(String.valueOf(envelope.opt(0)))) throw new Exception("VK Video требует авторизацию");
        JSONArray payload = envelope.optJSONArray(1);
        if (payload == null || payload.length() == 0) throw new Exception("VK Video не отдал данные ролика");
        Object optionsValue = payload.opt(payload.length() - 1);
        JSONObject options = optionsValue instanceof JSONObject ? (JSONObject) optionsValue : null;
        JSONObject player = options == null ? null : options.optJSONObject("player");
        JSONArray params = player == null ? null : player.optJSONArray("params");
        JSONObject data = params == null ? null : params.optJSONObject(0);
        if (data == null) throw new Exception("VK Video не отдал публичный поток");

        String hls = httpUrl(data.optString("hls"));
        if (hls == null) hls = httpUrl(data.optString("hls_fmp4"));
        String fallback = null;
        int bestHeight = 0;
        for (Iterator<String> it = data.keys(); it.hasNext();) {
            String key = it.next();
            String value = httpUrl(data.optString(key));
            if (value == null) continue;
            if (hls == null && key.startsWith("hls") && !key.contains("live_playback")) hls = value;
            int height = qualityHeight(key);
            if (height > bestHeight) {
                bestHeight = height;
                fallback = value;
            }
        }
        String stream = hls != null ? hls : fallback;
        if (stream == null) throw new Exception("Нет совместимого потока VK Video");
        int bestWidth = bestHeight <= 0 ? 0 : Math.round(bestHeight * 16f / 9f);
        PlaybackInfo result = new PlaybackInfo(stream, bestWidth, bestHeight);
        CACHE.put(cacheKey, result);
        return result;
    }

    private static boolean isHls(String value) {
        return value != null && (value.contains(".m3u8") || value.contains("ct=8"));
    }

    private static String jsonObjectAt(String value, int start) throws Exception {
        int depth = 0;
        boolean string = false;
        boolean escaped = false;
        for (int i = start; i < value.length(); i++) {
            char current = value.charAt(i);
            if (string) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') string = false;
                continue;
            }
            if (current == '"') string = true;
            else if (current == '{') depth++;
            else if (current == '}' && --depth == 0) return value.substring(start, i + 1);
        }
        throw new Exception("Повреждены данные ролика Дзен");
    }

    private static String findDzenId(String url) throws Exception {
        String marker = "/video/watch/";
        int start = url.indexOf(marker);
        if (start < 0) throw new Exception("Не найден ID Дзен");
        start += marker.length();
        int end = start;
        while (end < url.length()) {
            char value = url.charAt(end);
            if (!Character.isLetterOrDigit(value) && value != '-' && value != '_') break;
            end++;
        }
        if (end == start) throw new Exception("Не найден ID Дзен");
        return url.substring(start, end);
    }

    private static int qualityHeight(String key) {
        int start;
        if (key.startsWith("url")) start = 3;
        else if (key.startsWith("cache")) start = 5;
        else return 0;
        int end = start;
        while (end < key.length() && Character.isDigit(key.charAt(end))) end++;
        if (end == start) return 0;
        try { return Integer.parseInt(key.substring(start, end)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static String httpUrl(String value) {
        if (value == null || value.isEmpty()) return null;
        if (value.startsWith("//")) return "https:" + value;
        return value.startsWith("http") ? value : null;
    }

    private static int[] findMaxDimensions(String value) {
        int bestWidth = 0;
        int bestHeight = 0;
        for (int i = 1; i < value.length() - 1; i++) {
            if (value.charAt(i) != 'x') continue;
            int left = i - 1;
            while (left >= 0 && Character.isDigit(value.charAt(left))) left--;
            int right = i + 1;
            while (right < value.length() && Character.isDigit(value.charAt(right))) right++;
            if (left == i - 1 || right == i + 1) continue;
            try {
                int width = Integer.parseInt(value.substring(left + 1, i));
                int height = Integer.parseInt(value.substring(i + 1, right));
                if (width > bestWidth && width <= 7680 && height <= 4320) {
                    bestWidth = width;
                    bestHeight = height;
                }
            } catch (NumberFormatException ignored) { }
        }
        return new int[]{bestWidth, bestHeight};
    }

    private static String findRutubeId(String url) throws Exception {
        StringBuilder candidate = new StringBuilder(32);
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            boolean alphaNumeric = c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
            if (alphaNumeric) {
                candidate.append(c);
                if (candidate.length() == 32) return candidate.toString();
            } else {
                candidate.setLength(0);
            }
        }
        throw new Exception("Не найден ID RUTUBE");
    }

    private static String findVkId(String url) throws Exception {
        int from = 0;
        while (from < url.length()) {
            int marker = url.indexOf("video", from);
            if (marker < 0) break;
            int start = marker + 5;
            int cursor = start;
            if (cursor < url.length() && url.charAt(cursor) == '-') cursor++;
            int ownerStart = cursor;
            while (cursor < url.length() && Character.isDigit(url.charAt(cursor))) cursor++;
            if (cursor > ownerStart && cursor < url.length() && url.charAt(cursor) == '_') {
                cursor++;
                int videoStart = cursor;
                while (cursor < url.length() && Character.isDigit(url.charAt(cursor))) cursor++;
                if (cursor > videoStart) return url.substring(start, cursor);
            }
            from = marker + 5;
        }
        throw new Exception("Не найден ID VK Video");
    }

    private static String get(String address, String referer) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept", "application/json,text/html,*/*");
        connection.setRequestProperty("Referer", referer);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; Android TV) AppleWebKit/537.36");
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            InputStream input = connection.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream(64 * 1024);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            input.close();
            return output.toString(StandardCharsets.UTF_8.name());
        } finally {
            connection.disconnect();
        }
    }

    private static String postVk(String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("https://vk.com/al_video.php").openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(10_000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        connection.setRequestProperty("Accept", "application/json,*/*");
        connection.setRequestProperty("Referer", "https://vk.com/al_video.php");
        connection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                + "AppleWebKit/537.36 Chrome/150.0.0.0 Safari/537.36");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try {
            OutputStream output = connection.getOutputStream();
            output.write(bytes);
            output.close();
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("VK Video: HTTP " + code);
            InputStream input = connection.getInputStream();
            ByteArrayOutputStream response = new ByteArrayOutputStream(96 * 1024);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) response.write(buffer, 0, read);
            input.close();
            Charset charset = StandardCharsets.UTF_8;
            String type = connection.getContentType();
            if (type != null) {
                int marker = type.toLowerCase(Locale.ROOT).indexOf("charset=");
                if (marker >= 0) {
                    String name = type.substring(marker + 8).trim().replace("\"", "");
                    try { charset = Charset.forName(name); } catch (Exception ignored) { }
                }
            }
            return response.toString(charset.name());
        } finally {
            connection.disconnect();
        }
    }
}
