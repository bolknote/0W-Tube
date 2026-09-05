package ru.tubetv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Low-priority search and playback support for public videos in the PeerTube federation. */
final class PeerTubeClient {
    static final String SOURCE = "PEERTUBE";
    private static final String SEARCH_ENDPOINT =
            "https://sepiasearch.org/api/v1/search/videos";
    private static final String USER_AGENT = "0W-Tube/" + BuildConfig.VERSION_NAME + " PeerTube";
    private static final int MIN_DURATION_SECONDS = 40 * 60;
    private static final int SEARCH_LIMIT = 20;
    private static final int RESULT_LIMIT = 3;
    private static final int INSPECTION_LIMIT = 3;
    private static final int TIMEOUT_MS = 8_000;
    private static final int MAX_JSON_BYTES = 1024 * 1024;
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final int MAX_CACHE_ENTRIES = 32;
    private static final long CACHE_MS = 10 * 60 * 1000L;

    private static final Set<String> STOP_WORDS = setOf(
            "а", "без", "в", "во", "для", "до", "за", "и", "из", "или", "к", "ко",
            "на", "не", "о", "об", "от", "по", "под", "при", "про", "с", "со", "у",
            "a", "an", "and", "for", "from", "in", "of", "on", "or", "the", "to");
    private static final Set<String> QUERY_DECORATIONS = setOf(
            "фильм", "кино", "смотреть", "онлайн", "film", "movie", "online");
    private static final Set<String> UNWANTED = setOf(
            "тизер", "трейлер", "обзор", "фрагмент", "отрывок", "реакция", "short",
            "shorts", "teaser", "trailer", "review", "reaction", "clip");
    private static final String[] VIDEO_CODECS =
            {"av01", "avc1", "avc3", "hev1", "hvc1", "theora", "vp8", "vp9", "vp09"};
    private static final String[] AUDIO_CODECS =
            {"ac-3", "alac", "ec-3", "flac", "mp4a", "opus", "vorbis"};
    private static final Map<Character, String> TRANSLIT = transliterationTable();
    private static final ExpiringCache<String, Media> CACHE = new ExpiringCache<>(MAX_CACHE_ENTRIES, CACHE_MS);

    static List<VideoItem> search(String query, int minWidth, int thumbnailWidth) throws Exception {
        String normalized = queryForTitle(query);
        if (normalized.isEmpty()) return new ArrayList<>();

        LinkedHashMap<String, Candidate> candidates = new LinkedHashMap<>();
        addCandidates(candidates, searchOnce(normalized));
        List<VideoItem> result = inspectAccepted(query, candidates, minWidth, thumbnailWidth);

        String latin = transliterate(normalized);
        if (result.isEmpty() && !latin.isEmpty() && !latin.equals(normalized)
                && !Thread.currentThread().isInterrupted()) {
            addCandidates(candidates, searchOnce(latin));
            result = inspectAccepted(query, candidates, minWidth, thumbnailWidth);
        }
        return result;
    }

    static boolean isApiUrl(String value) {
        if (value == null || !value.contains("/api/v1/videos/")) return false;
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    static PlaybackInfo resolve(String apiUrl, boolean audioOnly, boolean fresh) throws Exception {
        Media media = loadMedia(apiUrl, fresh);
        if (audioOnly) {
            if (media.audioUrl == null) {
                throw new Exception("PeerTube не отдал отдельный или низкокачественный аудиопоток");
            }
            return new PlaybackInfo(media.audioUrl, media.audioMimeType,
                    media.maxWidth, media.maxHeight);
        }
        if (media.streamUrl == null) throw new Exception("PeerTube не отдал воспроизводимый поток");
        return new PlaybackInfo(media.streamUrl, media.streamMimeType,
                media.maxWidth, media.maxHeight);
    }

    private static List<VideoItem> inspectAccepted(String query,
                                                   LinkedHashMap<String, Candidate> candidates,
                                                   int minWidth, int thumbnailWidth) {
        List<RankedCandidate> accepted = new ArrayList<>();
        for (Candidate candidate : candidates.values()) {
            Evaluation evaluation = evaluate(query, candidate.title, candidate.durationSeconds);
            if (evaluation.accepted) accepted.add(new RankedCandidate(candidate, evaluation.score));
        }
        Collections.sort(accepted, (left, right) -> Integer.compare(right.score, left.score));

        List<VideoItem> result = new ArrayList<>();
        int inspected = 0;
        for (RankedCandidate ranked : accepted) {
            if (Thread.currentThread().isInterrupted() || inspected++ >= INSPECTION_LIMIT) break;
            Candidate candidate = ranked.candidate;
            try {
                Media media = loadMedia(candidate.apiUrl, false);
                if (media.streamUrl == null || media.maxWidth < minWidth) continue;
                String thumbnail = media.thumbnailFor(thumbnailWidth);
                if (thumbnail == null) thumbnail = candidate.thumbnail;
                long duration = media.durationSeconds > 0
                        ? media.durationSeconds * 1000L : candidate.durationSeconds * 1000L;
                result.add(new VideoItem(SOURCE, candidate.title, "", thumbnail,
                        candidate.apiUrl, candidate.pageUrl, duration)
                        .withQuality(media.maxWidth, media.maxHeight));
                if (result.size() >= RESULT_LIMIT) break;
            } catch (Exception ignored) {
                // Federated indexes routinely contain stopped or unreachable origins.
            }
        }
        return result;
    }

    private static JSONArray searchOnce(String query) throws Exception {
        String address = SEARCH_ENDPOINT
                + "?search=" + encode(query)
                + "&count=" + SEARCH_LIMIT
                + "&start=0&nsfw=false&isLive=false&durationMin=" + MIN_DURATION_SECONDS;
        return getJson(address, MAX_JSON_BYTES).optJSONArray("data");
    }

    private static void addCandidates(LinkedHashMap<String, Candidate> target, JSONArray values) {
        if (values == null) return;
        for (int i = 0; i < values.length(); i++) {
            JSONObject item = values.optJSONObject(i);
            Candidate candidate = Candidate.from(item);
            if (candidate != null && !target.containsKey(candidate.pageUrl)) {
                target.put(candidate.pageUrl, candidate);
            }
        }
    }

    static Evaluation evaluate(String query, String title, int durationSeconds) {
        if (durationSeconds < MIN_DURATION_SECONDS) return Evaluation.reject();
        String normalizedQuery = queryForTitle(query);
        List<String> nativeTitleWords = words(normalize(title));
        List<String> nativeQueryWords = words(normalizedQuery);
        for (String word : nativeTitleWords) {
            if (UNWANTED.contains(word) && !nativeQueryWords.contains(word)) {
                return Evaluation.reject();
            }
        }
        Evaluation nativeView = evaluateView(normalizedQuery, normalize(title));
        Evaluation latinView = evaluateView(
                transliterate(normalizedQuery), transliterate(normalize(title)));
        return latinView.accepted || latinView.score > nativeView.score ? latinView : nativeView;
    }

    private static Evaluation evaluateView(String query, String title) {
        List<String> queryPhrase = words(query);
        List<String> titlePhrase = words(title);
        List<String> queryWords = significantWords(queryPhrase);
        List<String> titleWords = significantWords(titlePhrase);
        if (queryWords.isEmpty() || titleWords.isEmpty()) return Evaluation.reject();

        for (String word : titleWords) {
            if (UNWANTED.contains(word) && !queryWords.contains(word)) return Evaluation.reject();
        }

        int ordered = orderedMatches(queryWords, titleWords);
        int contiguousStart = contiguousMatchStart(queryPhrase, titlePhrase);
        boolean contiguous = contiguousStart >= 0;
        boolean exact = contiguous && queryPhrase.size() == titlePhrase.size();
        int score = ordered * 5000 / Math.max(1, queryWords.size());
        if (contiguous) score += 8000;
        if (exact) score += 8000;
        score += Math.min(240, titlePhrase.size());

        if (queryWords.size() == 1) {
            boolean yearSuffix = contiguousStart == 0 && titlePhrase.size() == queryPhrase.size() + 1
                    && isYear(titlePhrase.get(titlePhrase.size() - 1));
            return exact || yearSuffix ? Evaluation.accept(score) : Evaluation.reject(score);
        }
        return contiguous && contiguousStart <= 1
                ? Evaluation.accept(score) : Evaluation.reject(score);
    }

    private static int orderedMatches(List<String> query, List<String> title) {
        int matched = 0;
        int start = 0;
        for (String queryWord : query) {
            for (int i = start; i < title.size(); i++) {
                if (tokenMatches(queryWord, title.get(i))) {
                    matched++;
                    start = i + 1;
                    break;
                }
            }
        }
        return matched;
    }

    private static int contiguousMatchStart(List<String> query, List<String> title) {
        if (query.isEmpty() || query.size() > title.size()) return -1;
        for (int start = 0; start <= title.size() - query.size(); start++) {
            boolean matches = true;
            for (int offset = 0; offset < query.size(); offset++) {
                if (!tokenMatches(query.get(offset), title.get(start + offset))) {
                    matches = false;
                    break;
                }
            }
            if (matches) return start;
        }
        return -1;
    }

    private static boolean tokenMatches(String left, String right) {
        if (left.equals(right)) return true;
        int shortest = Math.min(left.length(), right.length());
        if (shortest < 5) return false;
        int allowed = Math.max(left.length(), right.length()) <= 8 ? 1 : 2;
        return editDistance(left, right) <= allowed;
    }

    private static int editDistance(String left, String right) {
        if (left.length() < right.length()) {
            String swap = left;
            left = right;
            right = swap;
        }
        int[] previous = new int[right.length() + 1];
        for (int i = 0; i < previous.length; i++) previous[i] = i;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int substitution = previous[j - 1]
                        + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), substitution);
            }
            previous = current;
        }
        return previous[right.length()];
    }

    private static Media loadMedia(String apiUrl, boolean fresh) throws Exception {
        if (!isApiUrl(apiUrl)) throw new Exception("Неверный адрес PeerTube");
        Media cached = fresh ? null : CACHE.get(apiUrl);
        if (cached != null) return cached;

        JSONObject metadata = getJson(apiUrl, MAX_JSON_BYTES);
        String base = origin(apiUrl);
        List<Thumbnail> thumbnails = parseThumbnails(metadata, base);
        String bestDirectMuxed = null;
        int bestDirectMuxedHeight = -1;
        String lowestDirectMuxed = null;
        int lowestDirectMuxedHeight = Integer.MAX_VALUE;
        String directAudio = null;
        long directAudioSize = Long.MAX_VALUE;
        int maxWidth = 0;
        int maxHeight = 0;

        JSONArray directFiles = metadata.optJSONArray("files");
        FileSelection direct = parseFiles(directFiles, base);
        bestDirectMuxed = direct.bestMuxedUrl;
        bestDirectMuxedHeight = direct.bestMuxedHeight;
        lowestDirectMuxed = direct.lowestMuxedUrl;
        lowestDirectMuxedHeight = direct.lowestMuxedHeight;
        directAudio = direct.audioUrl;
        directAudioSize = direct.audioSize;
        maxWidth = direct.maxWidth;
        maxHeight = direct.maxHeight;

        String masterUrl = null;
        HlsInfo bestHls = null;
        boolean playlistSaysMuxed = false;
        JSONArray playlists = metadata.optJSONArray("streamingPlaylists");
        if (playlists != null) {
            for (int i = 0; i < playlists.length(); i++) {
                JSONObject playlist = playlists.optJSONObject(i);
                if (playlist == null) continue;
                FileSelection files = parseFiles(playlist.optJSONArray("files"), base);
                if (files.maxWidth > maxWidth) {
                    maxWidth = files.maxWidth;
                    maxHeight = files.maxHeight;
                }
                if (files.bestMuxedUrl != null) {
                    playlistSaysMuxed = true;
                    if (bestDirectMuxed == null || files.bestMuxedHeight > bestDirectMuxedHeight) {
                        bestDirectMuxed = files.bestMuxedUrl;
                        bestDirectMuxedHeight = files.bestMuxedHeight;
                    }
                    if (lowestDirectMuxed == null
                            || files.lowestMuxedHeight < lowestDirectMuxedHeight) {
                        lowestDirectMuxed = files.lowestMuxedUrl;
                        lowestDirectMuxedHeight = files.lowestMuxedHeight;
                    }
                }
                if (files.audioUrl != null && files.audioSize < directAudioSize) {
                    directAudio = files.audioUrl;
                    directAudioSize = files.audioSize;
                }
                String candidateMaster = httpUrl(playlist.optString("playlistUrl"), base);
                if (candidateMaster == null) continue;
                try {
                    String manifest = getText(candidateMaster, MAX_MANIFEST_BYTES);
                    HlsInfo info = parseHls(candidateMaster, manifest);
                    if (!info.valid) continue;
                    if (masterUrl == null || info.maxWidth > (bestHls == null ? 0 : bestHls.maxWidth)) {
                        masterUrl = candidateMaster;
                        bestHls = info;
                    }
                } catch (Exception ignored) { }
            }
        }

        if (bestHls != null && bestHls.maxWidth > maxWidth) {
            maxWidth = bestHls.maxWidth;
            maxHeight = bestHls.maxHeight;
        }
        boolean hlsPlayable = bestHls != null
                && (bestHls.muxed || bestHls.separateAudioUrl != null || playlistSaysMuxed);
        String streamUrl = hlsPlayable ? masterUrl : bestDirectMuxed;
        String streamMime = hlsPlayable ? "application/x-mpegURL" : null;
        String audioUrl = directAudio;
        String audioMime = directAudio == null ? null : "audio/mp4";
        if (audioUrl == null && bestHls != null && bestHls.separateAudioUrl != null) {
            audioUrl = bestHls.separateAudioUrl;
            audioMime = "application/x-mpegURL";
        }
        if (audioUrl == null && bestHls != null && bestHls.lowestMuxedUrl != null) {
            audioUrl = bestHls.lowestMuxedUrl;
            audioMime = "application/x-mpegURL";
        }
        if (audioUrl == null && lowestDirectMuxed != null) {
            audioUrl = lowestDirectMuxed;
            audioMime = null;
        }

        Media result = new Media(streamUrl, streamMime, audioUrl, audioMime,
                maxWidth, maxHeight, metadata.optLong("duration"), thumbnails);
        CACHE.put(apiUrl, result);
        return result;
    }

    private static FileSelection parseFiles(JSONArray files, String base) {
        FileSelection result = new FileSelection();
        if (files == null) return result;
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null) continue;
            String url = httpUrl(file.optString("fileUrl"), base);
            if (url == null) continue;
            JSONObject resolution = file.optJSONObject("resolution");
            int width = Math.max(0, file.optInt("width"));
            int height = Math.max(0, file.optInt("height"));
            if (height == 0 && resolution != null) height = Math.max(0, resolution.optInt("id"));
            if (width == 0 && height > 0) width = Math.round(height * 16f / 9f);
            if (width > result.maxWidth) {
                result.maxWidth = width;
                result.maxHeight = height;
            }
            boolean audioLabel = height == 0 || resolution != null
                    && resolution.optString("label").toLowerCase(Locale.ROOT).contains("audio");
            boolean hasVideo = file.has("hasVideo") ? file.optBoolean("hasVideo") : !audioLabel;
            boolean hasAudio = file.has("hasAudio") ? file.optBoolean("hasAudio") : audioLabel;
            long size = file.optLong("size", Long.MAX_VALUE);
            if (!hasVideo && hasAudio && size < result.audioSize) {
                result.audioUrl = url;
                result.audioSize = size;
            } else if (hasVideo && hasAudio) {
                if (height > result.bestMuxedHeight) {
                    result.bestMuxedUrl = url;
                    result.bestMuxedHeight = height;
                }
                if (height < result.lowestMuxedHeight) {
                    result.lowestMuxedUrl = url;
                    result.lowestMuxedHeight = height;
                }
            }
        }
        return result;
    }

    static HlsInfo parseHls(String playlistUrl, String manifest) {
        HlsInfo result = new HlsInfo();
        result.valid = manifest != null && manifest.trim().startsWith("#EXTM3U");
        if (!result.valid) return result;
        Map<String, String> audioGroups = new LinkedHashMap<>();
        String pending = null;
        for (String raw : manifest.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-MEDIA:")) {
                String type = hlsAttribute(line, "TYPE");
                String uri = hlsAttribute(line, "URI");
                String group = hlsAttribute(line, "GROUP-ID");
                if ("AUDIO".equalsIgnoreCase(type) && uri != null) {
                    String resolved = httpUrl(uri, playlistUrl);
                    if (resolved != null) {
                        audioGroups.put(group == null ? "" : group, resolved);
                        if (result.separateAudioUrl == null
                                || "YES".equalsIgnoreCase(hlsAttribute(line, "DEFAULT"))) {
                            result.separateAudioUrl = resolved;
                        }
                    }
                }
            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                pending = line;
            } else if (pending != null && !line.isEmpty() && !line.startsWith("#")) {
                String variant = httpUrl(line, playlistUrl);
                int[] dimensions = dimensions(hlsAttribute(pending, "RESOLUTION"));
                if (dimensions[0] > result.maxWidth) {
                    result.maxWidth = dimensions[0];
                    result.maxHeight = dimensions[1];
                }
                String codecs = hlsAttribute(pending, "CODECS");
                boolean video = dimensions[0] > 0 || containsPrefix(codecs, VIDEO_CODECS);
                boolean audio = containsPrefix(codecs, AUDIO_CODECS);
                String audioGroup = hlsAttribute(pending, "AUDIO");
                if (video && audio) {
                    result.muxed = true;
                    if (variant != null && (result.lowestMuxedUrl == null
                            || dimensions[1] < result.lowestMuxedHeight)) {
                        result.lowestMuxedUrl = variant;
                        result.lowestMuxedHeight = dimensions[1];
                    }
                }
                if (video && audioGroup != null && audioGroups.containsKey(audioGroup)) {
                    result.separateAudioUrl = audioGroups.get(audioGroup);
                }
                pending = null;
            }
        }
        return result;
    }

    private static List<Thumbnail> parseThumbnails(JSONObject metadata, String base) {
        LinkedHashMap<String, Thumbnail> result = new LinkedHashMap<>();
        JSONArray values = metadata.optJSONArray("thumbnails");
        if (values != null) {
            for (int i = 0; i < values.length(); i++) {
                JSONObject item = values.optJSONObject(i);
                if (item == null) continue;
                String url = httpUrl(item.optString("fileUrl"), base);
                if (url != null) result.put(url, new Thumbnail(url,
                        Math.max(0, item.optInt("width")), Math.max(0, item.optInt("height"))));
            }
        }
        addThumbnail(result, metadata.optString("thumbnailPath"), base);
        addThumbnail(result, metadata.optString("previewPath"), base);
        return new ArrayList<>(result.values());
    }

    private static void addThumbnail(Map<String, Thumbnail> values, String path, String base) {
        String url = httpUrl(path, base);
        if (url != null && !values.containsKey(url)) values.put(url, new Thumbnail(url, 0, 0));
    }

    private static JSONObject getJson(String address, int limit) throws Exception {
        return new JSONObject(getText(address, limit));
    }

    private static String getText(String address, int limit) throws Exception {
        BoundedHttpReader.checkInterrupted();
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json,application/vnd.apple.mpegurl,*/*");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("PeerTube: HTTP " + code);
            return BoundedHttpReader.readText(connection.getInputStream(), limit);
        } finally {
            connection.disconnect();
        }
    }

    private static String queryForTitle(String value) {
        List<String> values = words(normalize(value));
        while (!values.isEmpty()) {
            String last = values.get(values.size() - 1);
            if (QUERY_DECORATIONS.contains(last) || isYear(last)) values.remove(values.size() - 1);
            else break;
        }
        return join(values);
    }

    static String normalize(String value) {
        if (value == null) return "";
        value = value.toLowerCase(Locale.ROOT).replace('ё', 'е');
        StringBuilder result = new StringBuilder(value.length());
        boolean space = true;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isLetterOrDigit(current)) {
                result.append(current);
                space = false;
            } else if (!space) {
                result.append(' ');
                space = true;
            }
        }
        int length = result.length();
        if (length > 0 && result.charAt(length - 1) == ' ') result.setLength(length - 1);
        return result.toString();
    }

    static String transliterate(String value) {
        String normalized = normalize(value);
        StringBuilder result = new StringBuilder(normalized.length() * 2);
        for (int i = 0; i < normalized.length(); i++) {
            char current = normalized.charAt(i);
            String replacement = TRANSLIT.get(current);
            result.append(replacement == null ? String.valueOf(current) : replacement);
        }
        return normalize(result.toString());
    }

    private static List<String> words(String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isEmpty()) return result;
        for (String word : value.split(" ")) if (!word.isEmpty()) result.add(word);
        return result;
    }

    private static List<String> significantWords(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (!STOP_WORDS.contains(value) && value.length() >= 2) result.add(value);
        }
        return result.isEmpty() ? values : result;
    }

    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(' ');
            result.append(value);
        }
        return result.toString();
    }

    private static boolean isYear(String value) {
        if (value == null || value.length() != 4) return false;
        try {
            int year = Integer.parseInt(value);
            return year >= 1900 && year <= 2099;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static int[] dimensions(String value) {
        if (value == null) return new int[]{0, 0};
        int marker = value.indexOf('x');
        if (marker < 1 || marker >= value.length() - 1) return new int[]{0, 0};
        try {
            return new int[]{Integer.parseInt(value.substring(0, marker)),
                    Integer.parseInt(value.substring(marker + 1))};
        } catch (NumberFormatException ignored) {
            return new int[]{0, 0};
        }
    }

    private static boolean containsPrefix(String codecs, String[] prefixes) {
        if (codecs == null) return false;
        for (String codec : codecs.split(",")) {
            String value = codec.trim().toLowerCase(Locale.ROOT);
            for (String prefix : prefixes) if (value.startsWith(prefix)) return true;
        }
        return false;
    }

    private static String hlsAttribute(String line, String name) {
        String marker = name + "=";
        int start = line.indexOf(marker);
        while (start >= 0 && start > 0) {
            char before = line.charAt(start - 1);
            if (before == ':' || before == ',') break;
            start = line.indexOf(marker, start + marker.length());
        }
        if (start < 0) return null;
        start += marker.length();
        if (start < line.length() && line.charAt(start) == '"') {
            int end = line.indexOf('"', start + 1);
            return end > start ? line.substring(start + 1, end) : null;
        }
        int end = line.indexOf(',', start);
        if (end < 0) end = line.length();
        return line.substring(start, end).trim();
    }

    private static String origin(String address) throws Exception {
        URI uri = URI.create(address);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new Exception("Неверный адрес PeerTube");
        }
        int port = uri.getPort();
        return "https://" + uri.getHost() + (port < 0 ? "" : ":" + port) + "/";
    }

    private static String httpUrl(String value, String base) {
        if (value == null || value.isEmpty()) return null;
        try {
            URL resolved = base == null || base.isEmpty() ? new URL(value) : new URL(new URL(base), value);
            return "https".equalsIgnoreCase(resolved.getProtocol()) ? resolved.toString() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private static Set<String> setOf(String... values) {
        Set<String> result = new HashSet<>();
        Collections.addAll(result, values);
        return Collections.unmodifiableSet(result);
    }

    private static Map<Character, String> transliterationTable() {
        Map<Character, String> result = new LinkedHashMap<>();
        String source = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя";
        String[] replacements = {"a", "b", "v", "g", "d", "e", "yo", "zh", "z", "i", "y",
                "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "f", "kh", "ts", "ch",
                "sh", "shch", "", "y", "", "e", "yu", "ya"};
        for (int i = 0; i < source.length(); i++) result.put(source.charAt(i), replacements[i]);
        return result;
    }

    private static final class Candidate {
        final String title;
        final String pageUrl;
        final String apiUrl;
        final String thumbnail;
        final int durationSeconds;

        private Candidate(String title, String pageUrl, String apiUrl,
                          String thumbnail, int durationSeconds) {
            this.title = title;
            this.pageUrl = pageUrl;
            this.apiUrl = apiUrl;
            this.thumbnail = thumbnail;
            this.durationSeconds = durationSeconds;
        }

        static Candidate from(JSONObject item) {
            if (item == null) return null;
            String page = httpUrl(item.optString("url"), null);
            String uuid = item.optString("uuid");
            if (page == null || uuid.isEmpty()) return null;
            try {
                String api = origin(page) + "api/v1/videos/" + encode(uuid);
                return new Candidate(item.optString("name", "Видео PeerTube"), page, api,
                        httpUrl(item.optString("thumbnailUrl"), page),
                        Math.max(0, item.optInt("duration")));
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    static final class Evaluation {
        final boolean accepted;
        final int score;

        private Evaluation(boolean accepted, int score) {
            this.accepted = accepted;
            this.score = score;
        }

        static Evaluation accept(int score) { return new Evaluation(true, score); }
        static Evaluation reject() { return new Evaluation(false, -10_000); }
        static Evaluation reject(int score) { return new Evaluation(false, score); }
    }

    static final class HlsInfo {
        boolean valid;
        boolean muxed;
        int maxWidth;
        int maxHeight;
        String separateAudioUrl;
        String lowestMuxedUrl;
        int lowestMuxedHeight = Integer.MAX_VALUE;
    }

    private static final class RankedCandidate {
        final Candidate candidate;
        final int score;

        RankedCandidate(Candidate candidate, int score) {
            this.candidate = candidate;
            this.score = score;
        }
    }

    private static final class FileSelection {
        String bestMuxedUrl;
        int bestMuxedHeight = -1;
        String lowestMuxedUrl;
        int lowestMuxedHeight = Integer.MAX_VALUE;
        String audioUrl;
        long audioSize = Long.MAX_VALUE;
        int maxWidth;
        int maxHeight;
    }

    private static final class Thumbnail {
        final String url;
        final int width;
        final int height;

        Thumbnail(String url, int width, int height) {
            this.url = url;
            this.width = width;
            this.height = height;
        }
    }

    private static final class Media {
        final String streamUrl;
        final String streamMimeType;
        final String audioUrl;
        final String audioMimeType;
        final int maxWidth;
        final int maxHeight;
        final long durationSeconds;
        final List<Thumbnail> thumbnails;

        Media(String streamUrl, String streamMimeType, String audioUrl, String audioMimeType,
              int maxWidth, int maxHeight, long durationSeconds, List<Thumbnail> thumbnails) {
            this.streamUrl = streamUrl;
            this.streamMimeType = streamMimeType;
            this.audioUrl = audioUrl;
            this.audioMimeType = audioMimeType;
            this.maxWidth = maxWidth;
            this.maxHeight = maxHeight;
            this.durationSeconds = durationSeconds;
            this.thumbnails = thumbnails;
        }

        String thumbnailFor(int targetWidth) {
            Thumbnail best = null;
            Thumbnail largest = null;
            for (Thumbnail candidate : thumbnails) {
                if (candidate.width <= 0 || candidate.height <= 0
                        || candidate.width * 10 < candidate.height * 12) continue;
                if (largest == null || candidate.width > largest.width) largest = candidate;
                if (candidate.width >= targetWidth
                        && (best == null || candidate.width < best.width)) best = candidate;
            }
            if (best != null) return best.url;
            if (largest != null) return largest.url;
            return thumbnails.isEmpty() ? null : thumbnails.get(0).url;
        }
    }

    private PeerTubeClient() { }
}
