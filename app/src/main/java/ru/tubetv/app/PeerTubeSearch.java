package ru.tubetv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Small, bounded discovery layer; it owns no index or persistent search data. */
final class PeerTubeSearch {
    static final int MIN_DURATION_SECONDS = 40 * 60;
    private static final int SEARCH_LIMIT = 40;
    private static final int RESULT_LIMIT = 3;
    private static final int INSPECTION_LIMIT = 8;
    private static final long SEARCH_MS = 25_000;
    private static final long ROUND_MS = 8_000;
    private static final long INSPECTION_MS = 5_000;
    private static final String SEPIA = "https://sepiasearch.org/api/v1/search/videos";
    private static final String PEERSEEK = "https://peerseek.video/v1/search";
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHORT_ID = Pattern.compile("[a-zA-Z0-9]{22}");
    private static final ThreadPoolExecutor REQUESTS = new ThreadPoolExecutor(2, 2,
            30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), task -> {
                Thread thread = new Thread(task, "peertube-search");
                thread.setDaemon(true);
                return thread;
            });
    static { REQUESTS.allowCoreThreadTimeOut(true); }

    interface Fetcher { JSONObject get(String address, long deadline) throws Exception; }
    interface Inspector { VideoItem inspect(Candidate candidate, long deadline) throws Exception; }

    static List<VideoItem> search(String query, List<String> variants,
                                  Fetcher fetcher, Inspector inspector) {
        long deadline = after(SEARCH_MS);
        LinkedHashMap<String, Candidate> candidates = new LinkedHashMap<>();
        Set<String> inspected = new HashSet<>();
        List<VideoItem> result = new ArrayList<>();

        List<Request> primary = new ArrayList<>();
        for (String variant : variants) {
            primary.add(new Request(sepiaUrl('"' + variant + '"'), false));
            primary.add(new Request(PEERSEEK + "?q=" + encode(variant)
                    + "&size=" + SEARCH_LIMIT + "&page=1", true));
        }
        collect(primary, candidates, fetcher, deadline);
        List<Candidate> ranked = rank(query, candidates);
        // Broaden Sepia's phrase queries if the indexes returned too few candidate titles.
        boolean usedUnquoted = ranked.size() < RESULT_LIMIT;
        if (usedUnquoted) collect(unquotedRequests(variants), candidates, fetcher, deadline);
        inspect(query, candidates, inspected, result, inspector, deadline,
                usedUnquoted ? INSPECTION_LIMIT : 4);
        if (!usedUnquoted && result.size() < RESULT_LIMIT && active(deadline)) {
            collect(unquotedRequests(variants), candidates, fetcher, deadline);
            inspect(query, candidates, inspected, result, inspector, deadline, INSPECTION_LIMIT);
        }
        return result;
    }

    private static List<Request> unquotedRequests(List<String> variants) {
        List<Request> requests = new ArrayList<>();
        for (String variant : variants) requests.add(new Request(sepiaUrl(variant), false));
        return requests;
    }

    private static String sepiaUrl(String query) { return SEPIA + peerTubeParameters(query); }

    private static String peerTubeParameters(String query) {
        return "?search=" + encode(query) + "&count=" + SEARCH_LIMIT
                + "&start=0&nsfw=false&isLive=false&durationMin=" + MIN_DURATION_SECONDS;
    }

    private static void collect(List<Request> requests, LinkedHashMap<String, Candidate> candidates,
                                 Fetcher fetcher, long searchDeadline) {
        long deadline = Math.min(searchDeadline, after(ROUND_MS));
        List<Future<List<Candidate>>> pending = new ArrayList<>();
        try {
            for (Request request : requests) {
                if (!active(deadline)) break;
                try {
                    pending.add(REQUESTS.submit(() -> {
                        if (!active(deadline)) return new ArrayList<>();
                        JSONObject response = fetcher.get(request.address, deadline);
                        JSONArray items = response.optJSONArray(request.peerSeek ? "videos" : "data");
                        List<Candidate> found = new ArrayList<>();
                        if (items != null) {
                            for (int i = 0; i < Math.min(SEARCH_LIMIT, items.length()); i++) {
                                Candidate candidate = Candidate.from(items.optJSONObject(i), request.peerSeek);
                                if (candidate != null) found.add(candidate);
                            }
                        }
                        return found;
                    }));
                } catch (RejectedExecutionException busy) {
                    // Keep already submitted work; repeated cancellations must not grow the queue.
                    break;
                }
            }
            for (Future<List<Candidate>> future : pending) {
                if (Thread.currentThread().isInterrupted()) break;
                try {
                    long remaining = Math.max(0, deadline - System.nanoTime());
                    List<Candidate> found = future.get(remaining, TimeUnit.NANOSECONDS);
                    for (Candidate candidate : found) {
                        if (!candidates.containsKey(candidate.apiUrl)) {
                            candidates.put(candidate.apiUrl, candidate);
                        }
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ignored) {
                    // One failed index must not discard the other index's results.
                }
            }
        } finally {
            for (Future<?> future : pending) future.cancel(true);
            REQUESTS.purge();
        }
    }

    private static List<Candidate> rank(String query, LinkedHashMap<String, Candidate> candidates) {
        List<RankedCandidate> ranked = new ArrayList<>();
        for (Candidate candidate : candidates.values()) {
            PeerTubeClient.Evaluation evaluation = PeerTubeClient.evaluate(
                    query, candidate.title, candidate.durationSeconds);
            if (evaluation.accepted) ranked.add(new RankedCandidate(candidate, evaluation.score));
        }
        Collections.sort(ranked, (left, right) -> Integer.compare(right.score, left.score));
        List<Candidate> result = new ArrayList<>();
        for (RankedCandidate candidate : ranked) result.add(candidate.candidate);
        return result;
    }

    private static void inspect(String query, LinkedHashMap<String, Candidate> candidates,
                                Set<String> inspected, List<VideoItem> result,
                                Inspector inspector, long deadline, int inspectionLimit) {
        for (Candidate candidate : rank(query, candidates)) {
            if (!active(deadline) || inspected.size() >= inspectionLimit
                    || result.size() >= RESULT_LIMIT) break;
            if (!inspected.add(candidate.apiUrl)) continue;
            Future<VideoItem> pending = null;
            try {
                long inspectionDeadline = Math.min(deadline, after(INSPECTION_MS));
                pending = REQUESTS.submit(() -> inspector.inspect(candidate, inspectionDeadline));
                VideoItem item = pending.get(Math.max(0, inspectionDeadline - System.nanoTime()),
                        TimeUnit.NANOSECONDS);
                if (item != null) {
                    boolean duplicate = false;
                    for (VideoItem existing : result) {
                        if (existing.stableKey().equals(item.stableKey())) duplicate = true;
                    }
                    if (!duplicate) result.add(item);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ignored) {
                // Continue past dead origins rather than only trying the first three hits.
            } finally {
                if (pending != null) pending.cancel(true);
                REQUESTS.purge();
            }
        }
    }

    private static long after(long millis) { return System.nanoTime() + millis * 1_000_000L; }

    private static boolean active(long deadline) {
        return !Thread.currentThread().isInterrupted() && System.nanoTime() < deadline;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static String canonicalUuid(String value) {
        return value != null && UUID.matcher(value).matches() ? value.toLowerCase(Locale.ROOT) : null;
    }

    static final class Candidate {
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

        static Candidate from(JSONObject item, boolean peerSeek) {
            if (item == null || item.optBoolean("isLive") || item.optBoolean("nsfw")) return null;
            try {
                URI page = URI.create(item.optString(peerSeek ? "videoUrl" : "url"));
                if (!"https".equalsIgnoreCase(page.getScheme()) || page.getHost() == null
                        || page.getRawUserInfo() != null) return null;
                String uuid = item.optString("uuid");
                if (peerSeek) {
                    String path = page.getPath();
                    if (path.startsWith("/w/")) uuid = path.substring(3);
                    else if (path.startsWith("/videos/watch/")) uuid = path.substring(14);
                    else return null;
                }
                String canonical = canonicalUuid(uuid);
                if (canonical != null) uuid = canonical;
                else if (!SHORT_ID.matcher(uuid).matches()) return null;
                String origin = "https://" + page.getHost().toLowerCase(Locale.ROOT)
                        + (page.getPort() < 0 || page.getPort() == 443 ? "" : ":" + page.getPort());
                String thumbnail = item.optString("thumbnailUrl");
                URI image = null;
                try {
                    if (!thumbnail.isEmpty()) image = URI.create(origin + "/").resolve(thumbnail);
                    if (image != null && (!"https".equalsIgnoreCase(image.getScheme())
                            || image.getHost() == null)) image = null;
                } catch (IllegalArgumentException ignored) { }
                return new Candidate(item.optString(peerSeek ? "title" : "name", "Видео PeerTube"),
                        origin + "/videos/watch/" + uuid, origin + "/api/v1/videos/" + uuid,
                        image == null ? null : image.toString(),
                        Math.max(0, item.optInt(peerSeek ? "durationSeconds" : "duration")));
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static final class Request {
        final String address;
        final boolean peerSeek;

        Request(String address, boolean peerSeek) {
            this.address = address;
            this.peerSeek = peerSeek;
        }
    }

    private static final class RankedCandidate {
        final Candidate candidate;
        final int score;

        RankedCandidate(Candidate candidate, int score) {
            this.candidate = candidate;
            this.score = score;
        }
    }

    private PeerTubeSearch() { }
}
