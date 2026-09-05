package ru.tubetv.app;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;

final class StreamResolver {
    private static final long CACHE_MS = 10 * 60 * 1000L;
    private static final ExpiringCache<String, MediaCapabilities> CAPABILITIES =
            new ExpiringCache<>(256, 30 * 60 * 1000L);
    private static final ExpiringCache<String, DzenStreams> DZEN_STREAMS =
            new ExpiringCache<>(36, CACHE_MS);

    PlaybackInfo resolveForPlayback(String resolverUrl, boolean audioOnly) throws Exception {
        PlaybackInfo result = resolve(resolverUrl, audioOnly, true);
        if (result.maxWidth > 0) CAPABILITIES.put(resolverUrl, result.capabilities());
        return result;
    }

    MediaCapabilities inspect(String url) throws Exception {
        BoundedHttpReader.checkInterrupted();
        MediaCapabilities cached = CAPABILITIES.get(url);
        if (cached != null) return cached;
        MediaCapabilities result = resolve(url, false, false).capabilities();
        if (result.maxWidth > 0) CAPABILITIES.put(url, result);
        return result;
    }

    private PlaybackInfo resolve(String resolverUrl, boolean audioOnly, boolean forPlayback) throws Exception {
        BoundedHttpReader.checkInterrupted();
        if (PeerTubeClient.isApiUrl(resolverUrl)) {
            return PeerTubeClient.resolve(resolverUrl, audioOnly, forPlayback);
        }
        if (resolverUrl != null && (resolverUrl.contains("dzen.ru/video/")
                || resolverUrl.contains("zen.yandex.ru/video/"))) {
            return inspectDzen(resolverUrl, forPlayback, audioOnly);
        }
        if (resolverUrl != null && (resolverUrl.contains("vkvideo.ru/")
                || resolverUrl.contains("vk.com/video"))) {
            return inspectVk(resolverUrl, audioOnly);
        }
        if (OkClient.isOkUrl(resolverUrl)) {
            return inspectOk(resolverUrl, forPlayback, audioOnly);
        }
        return inspectRutube(resolverUrl, audioOnly);
    }

    static void cacheDzen(String id, JSONObject video, int maxWidth, int maxHeight) {
        if (id == null || id.isEmpty() || video == null) return;
        DZEN_STREAMS.put(id, DzenStreams.from(video, maxWidth, maxHeight));
        if (maxWidth > 0) {
            CAPABILITIES.put("https://dzen.ru/video/watch/" + id,
                    new MediaCapabilities(maxWidth, maxHeight));
        }
    }

    private PlaybackInfo inspectDzen(String url, boolean forPlayback, boolean audioOnly) throws Exception {
        String id = findDzenId(url);
        DzenStreams streams = forPlayback ? null : DZEN_STREAMS.get(id);
        if (streams == null) {
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
            streams = DzenStreams.from(video, video.optInt("width"), video.optInt("height"));
            DZEN_STREAMS.put(id, streams);
        }

        String stream;
        String mime;
        if (audioOnly && streams.dash != null
                && hasDashAudio(streams.dash, null, DzenClient.USER_AGENT)) {
            stream = streams.dash;
            mime = "application/dash+xml";
        } else if (streams.hls != null) {
            String separateAudio = audioOnly
                    ? findHlsAudioRendition(streams.hls, null, DzenClient.USER_AGENT) : null;
            stream = separateAudio != null ? separateAudio : streams.hls;
            mime = "application/x-mpegURL";
        } else {
            stream = audioOnly && streams.audioFallback != null
                    ? streams.audioFallback : streams.fallback;
            mime = null;
        }
        if (stream == null) throw new Exception("Нет совместимого потока Дзен");

        int maxWidth = streams.maxWidth;
        int maxHeight = streams.maxHeight;
        if (!forPlayback && maxWidth == 0 && streams.hls != null) {
            try {
                int[] dimensions = findMaxDimensions(
                        get(streams.hls, null, DzenClient.USER_AGENT));
                maxWidth = dimensions[0];
                maxHeight = dimensions[1];
            } catch (Exception ignored) { }
        }
        if (maxWidth == 0 && streams.fallback != null
                && streams.fallback.contains("type=5")) {
            maxWidth = 1920;
            maxHeight = 1080;
        }
        return new PlaybackInfo(stream, mime, maxWidth, maxHeight);
    }

    private PlaybackInfo inspectOk(String url, boolean forPlayback, boolean audioOnly) throws Exception {
        OkClient.PlayerData player = OkClient.loadPlayerData(url);
        if (player.externalUrl != null) {
            String external = player.externalUrl;
            if (external.contains("vkvideo.ru/") || external.contains("vk.com/video")) {
                return inspectVk(external, audioOnly);
            }
            if (external.contains("dzen.ru/video/") || external.contains("zen.yandex.ru/video/")) {
                return inspectDzen(external, forPlayback, audioOnly);
            }
            if (external.contains("rutube.ru/")) return inspectRutube(external, audioOnly);
            throw new Exception("Видео OK размещено на неподдерживаемом внешнем сервисе");
        }
        if (player.mobileUrl != null) {
            return new PlaybackInfo(player.mobileUrl, 0, 0);
        }

        JSONObject metadata = player.metadata;
        if (metadata == null) throw new Exception("OK не отдал метаданные ролика");
        JSONObject movie = metadata.optJSONObject("movie");
        if ("USER_YOUTUBE".equals(metadata.optString("provider"))) {
            throw new Exception("Видео OK размещено на YouTube и пока не поддерживается");
        }

        String hls = firstHttp(metadata, "hlsManifestUrl", "ondemandHls",
                "hlsMasterPlaylistUrl");
        String dash = firstHttp(metadata, "ondemandDash", "metadataWebmUrl");
        String metadataUrl = httpUrl(metadata.optString("metadataUrl"));
        if (dash == null && isDash(metadataUrl)) dash = metadataUrl;

        String bestDirect = null;
        int bestRank = Integer.MIN_VALUE;
        String lowestDirect = null;
        int lowestRank = Integer.MAX_VALUE;
        JSONArray videos = metadata.optJSONArray("videos");
        if (videos != null) {
            for (int i = 0; i < videos.length(); i++) {
                JSONObject format = videos.optJSONObject(i);
                String candidate = format == null ? null : httpUrl(format.optString("url"));
                if (candidate == null) continue;
                int rank = okQualityRank(format.optString("name"), candidate);
                if (bestDirect == null || rank > bestRank) {
                    bestDirect = candidate;
                    bestRank = rank;
                }
                if (lowestDirect == null || rank < lowestRank) {
                    lowestDirect = candidate;
                    lowestRank = rank;
                }
            }
        }

        int maxWidth = movie == null ? 0 : Math.max(0, movie.optInt("width"));
        int maxHeight = movie == null ? 0 : Math.max(0, movie.optInt("height"));
        if (!forPlayback) {
            int[] manifestDimensions = new int[]{0, 0};
            if (hls != null) {
                try {
                    manifestDimensions = findMaxDimensions(OkClient.getCdnText(hls));
                } catch (Exception ignored) { }
            }
            if (dash != null) {
                try {
                    int[] dashDimensions = findMaxDimensions(OkClient.getCdnText(dash));
                    if (dashDimensions[0] > manifestDimensions[0]) {
                        manifestDimensions = dashDimensions;
                    }
                } catch (Exception ignored) { }
            }
            String embeddedDash = metadata.optString("metadataEmbedded");
            if (!embeddedDash.isEmpty()) {
                int[] embeddedDimensions = findMaxDimensions(embeddedDash);
                if (embeddedDimensions[0] > manifestDimensions[0]) {
                    manifestDimensions = embeddedDimensions;
                }
            }
            if (manifestDimensions[0] > 0) {
                maxWidth = manifestDimensions[0];
                maxHeight = manifestDimensions[1];
            }
        }

        String stream;
        String mime;
        if (audioOnly && dash != null && hasOkDashAudio(dash)) {
            stream = dash;
            mime = "application/dash+xml";
        } else if (hls != null) {
            stream = hls;
            mime = "application/x-mpegURL";
        } else if (dash != null) {
            stream = dash;
            mime = "application/dash+xml";
        } else {
            stream = audioOnly && lowestDirect != null ? lowestDirect : bestDirect;
            mime = null;
        }
        if (stream == null) {
            Object paymentInfo = metadata.opt("paymentInfo");
            if (paymentInfo != null && paymentInfo != JSONObject.NULL) {
                throw new Exception("Видео OK платное");
            }
            throw new Exception("Нет совместимого потока OK");
        }
        return new PlaybackInfo(stream, mime, maxWidth, maxHeight);
    }

    private PlaybackInfo inspectRutube(String url, boolean audioOnly) throws Exception {
        String id = findRutubeId(url);
        String privateKey = queryParameter(url, "p");
        String optionsUrl = "https://rutube.ru/api/play/options/" + id
                + "/?format=json&no_404=true&mq=all";
        if (privateKey != null && !privateKey.isEmpty()) {
            optionsUrl += "&p=" + URLEncoder.encode(privateKey, "UTF-8");
        }
        String referer = url != null && url.startsWith("http")
                ? url : "https://rutube.ru/video/" + id + "/";
        JSONObject options = new JSONObject(get(optionsUrl, referer));
        JSONObject detail = options.optJSONObject("detail");
        if (detail != null) {
            String reason = null;
            JSONArray languages = detail.optJSONArray("languages");
            JSONObject language = languages == null ? null : languages.optJSONObject(0);
            if (language != null) reason = language.optString("title", null);
            if (reason == null || reason.isEmpty()) reason = detail.optString("type", null);
            throw new Exception(reason == null || reason.isEmpty()
                    ? "Видео RUTUBE недоступно"
                    : "Видео RUTUBE недоступно: " + reason);
        }
        JSONObject balancer = options.optJSONObject("video_balancer");
        if (balancer == null) throw new Exception("RUTUBE не отдал поток");
        String fallback = null;
        String hls = null;
        String dash = null;
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
            if (dash == null && isDash(value)) dash = value;
            if (hls == null && isHls(value)) hls = value;
            if (fallback == null && value.startsWith("http")) fallback = value;
        }
        String stream;
        String mime;
        if (audioOnly && dash != null && hasDashAudio(dash, referer, null)) {
            stream = dash;
            mime = "application/dash+xml";
        } else if (hls != null) {
            String separateAudio = audioOnly
                    ? findHlsAudioRendition(hls, referer, null) : null;
            stream = separateAudio != null ? separateAudio : hls;
            mime = "application/x-mpegURL";
        } else {
            stream = fallback;
            mime = null;
        }
        if (stream == null) throw new Exception("Нет совместимого потока RUTUBE");
        return new PlaybackInfo(stream, mime, maxWidth, maxHeight);
    }

    private static String queryParameter(String url, String name) {
        if (url == null || url.isEmpty()) return null;
        try {
            return Uri.parse(url).getQueryParameter(name);
        } catch (Exception ignored) {
            return null;
        }
    }

    private PlaybackInfo inspectVk(String url, boolean audioOnly) throws Exception {
        String id = VkVideoId.fromUrl(url);
        Exception apiError;
        try {
            return inspectVkApi(id, audioOnly);
        } catch (Exception error) {
            BoundedHttpReader.checkInterrupted();
            apiError = error;
        }

        try {
            return inspectVkLegacy(id, audioOnly);
        } catch (Exception legacyError) {
            legacyError.addSuppressed(apiError);
            throw legacyError;
        }
    }

    private PlaybackInfo inspectVkApi(String id, boolean audioOnly) throws Exception {
        JSONObject video = VkWebClient.getVideoById(id);
        JSONObject files = video.optJSONObject("files");
        if (files == null) throw new Exception("VK Video не отдал публичный поток");
        return inspectVkFiles(files, video.optInt("width"), video.optInt("height"), audioOnly);
    }

    private PlaybackInfo inspectVkLegacy(String id, boolean audioOnly) throws Exception {
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
        return inspectVkFiles(data, 0, 0, audioOnly);
    }

    static PlaybackInfo inspectVkFiles(JSONObject files, int reportedWidth, int reportedHeight,
                                       boolean audioOnly) throws Exception {
        String hls = httpUrl(files.optString("hls"));
        if (hls == null) hls = httpUrl(files.optString("hls_fmp4"));
        if (hls == null) hls = httpUrl(files.optString("hls_streams"));
        String dash = httpUrl(files.optString("dash_sep"));
        if (dash == null) dash = httpUrl(files.optString("dash"));
        if (dash == null) dash = httpUrl(files.optString("dash_streams"));
        String fallback = null;
        int bestHeight = 0;
        String lowestFallback = null;
        int lowestHeight = Integer.MAX_VALUE;
        for (Iterator<String> it = files.keys(); it.hasNext();) {
            String key = it.next();
            String value = httpUrl(files.optString(key));
            if (value == null) continue;
            if (hls == null && key.startsWith("hls") && !key.contains("live_playback")) hls = value;
            if (dash == null && key.startsWith("dash") && !key.contains("live_playback")
                    && !"dash_uni".equals(key)) dash = value;
            int height = qualityHeight(key);
            if (height > bestHeight) {
                bestHeight = height;
                fallback = value;
            }
            if (height > 0 && height < lowestHeight) {
                lowestHeight = height;
                lowestFallback = value;
            }
        }
        String stream;
        String mime;
        if (audioOnly && dash != null && hasDashAudio(dash, "https://vkvideo.ru/", null)) {
            stream = dash;
            mime = "application/dash+xml";
        } else if (hls != null) {
            String separateAudio = audioOnly ? findHlsAudioRendition(hls,
                    "https://vkvideo.ru/", null) : null;
            stream = separateAudio != null ? separateAudio : hls;
            mime = "application/x-mpegURL";
        } else if (fallback == null && dash != null) {
            stream = dash;
            mime = "application/dash+xml";
        } else {
            stream = audioOnly && lowestFallback != null ? lowestFallback : fallback;
            mime = null;
        }
        if (stream == null) throw new Exception("Нет совместимого потока VK Video");
        if (bestHeight <= 0) bestHeight = Math.max(0, reportedHeight);
        int bestWidth = bestHeight <= 0 ? Math.max(0, reportedWidth)
                : reportedWidth > 0 && reportedHeight == bestHeight
                ? reportedWidth : Math.round(bestHeight * 16f / 9f);
        return new PlaybackInfo(stream, mime, bestWidth, bestHeight);
    }

    private static boolean isHls(String value) {
        return value != null && (value.contains(".m3u8") || value.contains("ct=8"));
    }

    private static boolean isDash(String value) {
        return value != null && (value.contains(".mpd") || value.contains("ct=6"));
    }

    private static String firstHttp(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = httpUrl(object.optString(key));
            if (value != null) return value;
        }
        return null;
    }

    private static boolean hasOkDashAudio(String dashUrl) {
        try {
            String manifest = OkClient.getCdnText(dashUrl);
            return manifest.contains("contentType=\"audio\"")
                    || manifest.contains("mimeType=\"audio/")
                    || manifest.contains("<AudioChannelConfiguration");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int okQualityRank(String name, String url) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.US);
        if ("mobile".equals(normalized)) return 0;
        if ("lowest".equals(normalized)) return 1;
        if ("low".equals(normalized)) return 2;
        if ("sd".equals(normalized)) return 3;
        if ("hd".equals(normalized)) return 4;
        if ("full".equals(normalized)) return 5;
        if ("quad".equals(normalized)) return 6;
        if ("ultra".equals(normalized)) return 7;
        String[] types = {"4", "0", "1", "2", "3", "5", "6", "7"};
        for (int i = 0; i < types.length; i++) {
            if (url.contains("type=" + types[i]) || url.contains("type/" + types[i])) return i;
        }
        return -1;
    }

    private static String findHlsAudioRendition(String hlsUrl, String referer, String userAgent) {
        try {
            String manifest = userAgent == null ? get(hlsUrl, referer) : get(hlsUrl, referer, userAgent);
            String first = null;
            for (String line : manifest.split("\\r?\\n")) {
                if (!line.startsWith("#EXT-X-MEDIA:") || !line.contains("TYPE=AUDIO")) continue;
                String uri = attribute(line, "URI");
                if (uri == null) continue;
                String resolved = new URL(new URL(hlsUrl), uri).toString();
                if (line.contains("DEFAULT=YES")) return resolved;
                if (first == null) first = resolved;
            }
            return first;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean hasDashAudio(String dashUrl, String referer, String userAgent) {
        try {
            String manifest = userAgent == null ? get(dashUrl, referer) : get(dashUrl, referer, userAgent);
            return manifest.contains("contentType=\"audio\"")
                    || manifest.contains("mimeType=\"audio/")
                    || manifest.contains("<AudioChannelConfiguration");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String attribute(String line, String name) {
        String marker = name + "=\"";
        int start = line.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int end = line.indexOf('"', start);
        return end > start ? line.substring(start, end) : null;
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
        else if (key.startsWith("mp4_")) start = 4;
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

    private static final class DzenStreams {
        final String hls;
        final String dash;
        final String fallback;
        final String audioFallback;
        final int maxWidth;
        final int maxHeight;

        private DzenStreams(String hls, String dash, String fallback,
                            String audioFallback, int maxWidth, int maxHeight) {
            this.hls = hls;
            this.dash = dash;
            this.fallback = fallback;
            this.audioFallback = audioFallback;
            this.maxWidth = maxWidth;
            this.maxHeight = maxHeight;
        }

        static DzenStreams from(JSONObject video, int reportedWidth, int reportedHeight) {
            String hls = null;
            String dash = null;
            String fallback = null;
            String audioFallback = null;
            String direct = httpUrl(video.optString("id"));
            if (isDash(direct)) dash = direct;
            else if (isHls(direct)) hls = direct;
            else if (direct != null) {
                fallback = direct;
                audioFallback = direct;
            }
            JSONArray streams = video.optJSONArray("streams");
            if (streams != null) {
                for (int i = 0; i < streams.length(); i++) {
                    String candidate = httpUrl(streams.optString(i));
                    if (candidate == null) continue;
                    if (dash == null && isDash(candidate)) dash = candidate;
                    if (hls == null && isHls(candidate)) hls = candidate;
                    if (fallback == null && candidate.contains("ct=0")) fallback = candidate;
                    if (candidate.contains("ct=0")
                            && (audioFallback == null || candidate.contains("type=4"))) {
                        audioFallback = candidate;
                    }
                }
            }
            JSONArray oneVideo = video.optJSONArray("oneVideoStreams");
            if (oneVideo != null) {
                for (int i = 0; i < oneVideo.length(); i++) {
                    JSONObject item = oneVideo.optJSONObject(i);
                    String candidate = item == null ? null : httpUrl(item.optString("url"));
                    if (candidate == null) continue;
                    String type = item.optString("type");
                    if (dash == null && ("dash".equals(type) || isDash(candidate))) {
                        dash = candidate;
                    }
                    if (hls == null && ("hls".equals(type) || isHls(candidate))) {
                        hls = candidate;
                    }
                    if (fallback == null && "fullhd".equals(type)) fallback = candidate;
                    if (candidate.contains("ct=0")
                            && (audioFallback == null || candidate.contains("type=4"))) {
                        audioFallback = candidate;
                    }
                }
            }
            return new DzenStreams(hls, dash, fallback, audioFallback,
                    reportedWidth, reportedHeight);
        }
    }

    private static String get(String address, String referer) throws Exception {
        return get(address, referer,
                "Mozilla/5.0 (Linux; Android 11; Android TV) AppleWebKit/537.36");
    }

    private static String get(String address, String referer, String userAgent) throws Exception {
        BoundedHttpReader.checkInterrupted();
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept", "application/json,text/html,*/*");
        if (referer != null && !referer.isEmpty()) connection.setRequestProperty("Referer", referer);
        connection.setRequestProperty("User-Agent", userAgent == null ?
                "Mozilla/5.0 (Linux; Android 11; Android TV) AppleWebKit/537.36" : userAgent);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            return BoundedHttpReader.readText(connection.getInputStream(), BoundedHttpReader.MANIFEST_LIMIT);
        } finally {
            connection.disconnect();
        }
    }

    private static String postVk(String body) throws Exception {
        BoundedHttpReader.checkInterrupted();
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
            Charset charset = StandardCharsets.UTF_8;
            String type = connection.getContentType();
            if (type != null) {
                int marker = type.toLowerCase(Locale.ROOT).indexOf("charset=");
                if (marker >= 0) {
                    String name = type.substring(marker + 8).trim().replace("\"", "");
                    try { charset = Charset.forName(name); } catch (Exception ignored) { }
                }
            }
            return BoundedHttpReader.readText(connection.getInputStream(), BoundedHttpReader.JSON_LIMIT, charset);
        } finally {
            connection.disconnect();
        }
    }
}
