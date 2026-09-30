package ru.tubetv.app;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs the production Java search on a shared live snapshot, not a second title-filter implementation. */
public final class PeerTubeSearchBenchmark {
    public static void main(String[] args) throws Exception {
        Path titles = Path.of(args.length > 0 ? args[0] : "research/roskino_top100.tsv");
        Path output = Path.of(args.length > 1 ? args[1] : "/tmp/owtube-peertube-search-comparison.json");
        int limit = args.length > 2 ? Integer.parseInt(args[2]) : 100;
        JSONObject report = new JSONObject().put("generatedAt", Instant.now().toString())
                .put("method", "Shared live search/metadata snapshot; old search policy vs production Java search")
                .put("timing", "Discovery is prefetched; replay times are not phone performance measurements")
                .put("films", new JSONArray());
        int oldFound = 0, newFound = 0, oldCards = 0, newCards = 0, checked = 0;
        ExecutorService downloads = Executors.newFixedThreadPool(4);
        List<String> rows = Files.readAllLines(titles, StandardCharsets.UTF_8);
        try {
            for (String row : rows.subList(1, rows.size())) {
                if (checked >= limit) break;
                String[] fields = row.split("\t");
                String title = fields[1];
                Snapshot snapshot = new Snapshot();
                List<String> addresses = Collections.synchronizedList(new ArrayList<>());
                // Empty responses enumerate the bounded fallback plan using the application itself.
                PeerTubeClient.search(title, 0, 320, (address, bytes, deadline) -> {
                    addresses.add(address);
                    return "{\"data\":[],\"videos\":[]}";
                });
                List<Future<?>> pending = new ArrayList<>();
                for (String address : addresses) pending.add(downloads.submit(() -> {
                    try { snapshot.read(address, 1024 * 1024, after(8_000)); }
                    catch (Exception ignored) { }
                }));
                for (Future<?> future : pending) future.get();

                List<VideoItem> before = oldSearch(title, snapshot);
                List<VideoItem> after = PeerTubeClient.search(title, 0, 320, snapshot);
                if (!before.isEmpty()) oldFound++;
                if (!after.isEmpty()) newFound++;
                oldCards += before.size();
                newCards += after.size();
                JSONObject film = new JSONObject().put("rank", Integer.parseInt(fields[0]))
                        .put("title", title).put("year", Integer.parseInt(fields[2]))
                        .put("before", cards(before, snapshot)).put("after", cards(after, snapshot))
                        .put("searchFailures", snapshot.failures());
                report.getJSONArray("films").put(film);
                checked++;
                report.put("summary", new JSONObject().put("checked", checked)
                        .put("beforeFilms", oldFound).put("afterFilms", newFound)
                        .put("beforeCards", oldCards).put("afterCards", newCards));
                Files.writeString(output, report.toString(2), StandardCharsets.UTF_8);
                System.out.printf("[%3d] %d -> %d  %s%s%n", checked, before.size(), after.size(), title,
                        after.isEmpty() ? "" : "  " + after.get(0).pageUrl);
            }
        } finally {
            downloads.shutdownNow();
        }
        System.out.println(report.getJSONObject("summary"));
        System.out.println("Report: " + output.toAbsolutePath());
    }

    private static List<VideoItem> oldSearch(String query, Snapshot snapshot) throws Exception {
        LinkedHashMap<String, PeerTubeSearch.Candidate> candidates = new LinkedHashMap<>();
        String nativeQuery = PeerTubeClient.normalize(query);
        List<VideoItem> result = oldRound(query, nativeQuery, candidates, snapshot);
        String latin = PeerTubeClient.transliterate(nativeQuery);
        if (result.isEmpty() && !latin.equals(nativeQuery)) result = oldRound(query, latin, candidates, snapshot);
        return result;
    }

    private static List<VideoItem> oldRound(String query, String search,
                                           LinkedHashMap<String, PeerTubeSearch.Candidate> candidates,
                                           Snapshot snapshot) throws Exception {
        // Use the same first 20 search hits from the 40-hit snapshot for a fair comparison.
        String address = "https://sepiasearch.org/api/v1/search/videos?search="
                + URLEncoder.encode(search, StandardCharsets.UTF_8)
                + "&count=40&start=0&nsfw=false&isLive=false&durationMin=2400";
        try {
            JSONArray values = new JSONObject(snapshot.read(address, 1024 * 1024, after(8_000)))
                    .optJSONArray("data");
            if (values != null) for (int i = 0; i < Math.min(20, values.length()); i++) {
                PeerTubeSearch.Candidate candidate = PeerTubeSearch.Candidate.from(values.optJSONObject(i), false);
                if (candidate != null) candidates.putIfAbsent(candidate.apiUrl, candidate);
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        List<PeerTubeSearch.Candidate> ranked = new ArrayList<>();
        for (PeerTubeSearch.Candidate candidate : candidates.values()) {
            if (PeerTubeClient.evaluate(query, candidate.title, candidate.durationSeconds).accepted) ranked.add(candidate);
        }
        ranked.sort((left, right) -> Integer.compare(
                PeerTubeClient.evaluate(query, right.title, right.durationSeconds).score,
                PeerTubeClient.evaluate(query, left.title, left.durationSeconds).score));
        List<VideoItem> result = new ArrayList<>();
        for (int i = 0; i < Math.min(3, ranked.size()); i++) {
            try {
                VideoItem item = PeerTubeClient.inspectCandidate(ranked.get(i), 0, 320, snapshot, after(8_000));
                if (item != null) result.add(item);
            } catch (Exception ignored) { }
        }
        return result;
    }

    private static JSONArray cards(List<VideoItem> values, Snapshot snapshot) throws Exception {
        JSONArray result = new JSONArray();
        for (VideoItem item : values) result.put(new JSONObject().put("title", item.title)
                .put("url", item.pageUrl).put("width", item.maxWidth).put("height", item.maxHeight)
                .put("duration", item.durationMs).put("sources", snapshot.sources(item.playUrl)));
        return result;
    }

    private static long after(long millis) { return System.nanoTime() + millis * 1_000_000L; }

    private static final class Snapshot implements PeerTubeClient.TextReader {
        final Map<String, String> values = new ConcurrentHashMap<>();
        final Map<String, Exception> errors = new ConcurrentHashMap<>();

        @Override public String read(String address, int limit, long deadline) throws Exception {
            if (values.containsKey(address)) return values.get(address);
            if (errors.containsKey(address)) throw errors.get(address);
            HttpURLConnection connection = (HttpURLConnection) URI.create(address).toURL().openConnection();
            int timeout = (int) Math.min(4_000, Math.max(1, (deadline - System.nanoTime()) / 1_000_000));
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            connection.setRequestProperty("User-Agent", "0W-Tube PeerTube search benchmark/2");
            connection.setRequestProperty("Accept", "application/json,application/vnd.apple.mpegurl,*/*");
            try {
                if (connection.getResponseCode() != 200) throw new Exception("HTTP " + connection.getResponseCode());
                String value = BoundedHttpReader.readText(connection.getInputStream(), limit);
                values.put(address, value);
                return value;
            } catch (Exception error) {
                errors.put(address, error);
                throw error;
            } finally {
                connection.disconnect();
            }
        }

        JSONObject failures() throws Exception {
            JSONObject result = new JSONObject();
            for (Map.Entry<String, Exception> error : errors.entrySet()) {
                result.put(error.getKey(), error.getValue().toString());
            }
            return result;
        }

        JSONArray sources(String apiUrl) {
            java.util.Set<String> sources = new java.util.TreeSet<>();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (!entry.getKey().contains("/search")) continue;
                try {
                    boolean peerSeek = entry.getKey().contains("peerseek.video");
                    JSONArray items = new JSONObject(entry.getValue()).optJSONArray(peerSeek ? "videos" : "data");
                    if (items == null) continue;
                    for (int i = 0; i < items.length(); i++) {
                        PeerTubeSearch.Candidate candidate = PeerTubeSearch.Candidate.from(items.optJSONObject(i), peerSeek);
                        if (candidate != null && candidate.apiUrl.equals(apiUrl)) {
                            sources.add(URI.create(entry.getKey()).getHost());
                        }
                    }
                } catch (Exception ignored) { }
            }
            return new JSONArray(sources);
        }
    }

    private PeerTubeSearchBenchmark() { }
}
