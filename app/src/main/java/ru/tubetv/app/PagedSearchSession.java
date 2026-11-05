package ru.tubetv.app;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Query-local buffered pagination. Loading is serialized; quality completions may arrive from UI. */
final class PagedSearchSession {
    static final int TARGET = 12;
    static final int MAX_PAGES = 4;
    static final int MAX_CANDIDATES = 120;
    static final int MAX_QUALITY_PROBES = 48;

    interface PageLoader { Page load(String cursor) throws Exception; }
    interface QualityInspector { VideoItem inspect(VideoItem item) throws Exception; }
    interface PageListener { boolean onPage(List<VideoItem> items); }

    static final class Page {
        final List<VideoItem> items;
        final String next;

        Page(List<VideoItem> items, String next) {
            this.items = items;
            this.next = next == null || next.isEmpty() ? null : next;
        }
    }

    private final PageLoader loader;
    private final QualityInspector inspector;
    private final SearchTitleFilter titleFilter;
    private final List<VideoItem> candidates = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();
    private final Set<String> requestedCursors = new HashSet<>();
    private final Set<String> allocatedProbes = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ConcurrentMap<String, VideoItem> completedProbes = new ConcurrentHashMap<>();
    private String next;
    private int requests;
    private int delivered;
    private boolean exhausted;

    PagedSearchSession(PageLoader loader) {
        this("", loader);
    }

    PagedSearchSession(String query, PageLoader loader) {
        this(query, loader, item -> {
            MediaCapabilities info = new StreamResolver().inspect(item.playUrl);
            return item.withQuality(info.maxWidth, info.maxHeight);
        });
    }

    PagedSearchSession(PageLoader loader, QualityInspector inspector) {
        this("", loader, inspector);
    }

    PagedSearchSession(String query, PageLoader loader, QualityInspector inspector) {
        this.loader = loader;
        this.inspector = inspector;
        this.titleFilter = new SearchTitleFilter(query);
    }

    boolean needsMore(int minWidth) {
        applyQualities();
        if (minWidth > 0 && completedProbes.size() < allocatedProbes.size()) return false;
        if (matching(minWidth, delivered) >= TARGET) return false;
        if (delivered < candidates.size()) return true;
        return !exhausted && requests < MAX_PAGES && seen.size() < MAX_CANDIDATES
                && (minWidth <= 0 || allocatedProbes.size() < MAX_QUALITY_PROBES);
    }

    int requestCount() { return requests; }
    int qualityProbeCount() { return allocatedProbes.size(); }

    boolean allowsQualityProbe(VideoItem item) {
        String key = item.stableKey();
        return allocatedProbes.contains(key) && !completedProbes.containsKey(key);
    }

    void completeQualities(List<VideoItem> requested, List<VideoItem> inspected) {
        Map<String, VideoItem> results = new HashMap<>();
        for (VideoItem item : inspected) results.put(item.stableKey(), item);
        for (VideoItem item : requested) {
            String key = item.stableKey();
            if (allocatedProbes.contains(key)) {
                VideoItem result = results.get(key);
                completedProbes.put(key, result == null ? item : result);
            }
        }
    }

    int searchPages(int minWidth, PageListener listener) throws Exception {
        int total = 0;
        while (!Thread.currentThread().isInterrupted() && needsMore(minWidth)) {
            if (delivered < candidates.size()) {
                int end = batchEnd(minWidth);
                List<VideoItem> page = new ArrayList<>(candidates.subList(delivered, end));
                List<VideoItem> unknown = reserveProbes(page);
                if (minWidth > 0 && !unknown.isEmpty()) {
                    completeQualities(unknown, ParallelTasks.map(unknown, 4, inspector::inspect));
                    applyQualities();
                    int matching = matching(minWidth, delivered);
                    for (int i = delivered; i < end; i++) {
                        if (candidates.get(i).maxWidth >= minWidth && ++matching >= TARGET) {
                            end = i + 1;
                            break;
                        }
                    }
                }
                if (Thread.currentThread().isInterrupted()) break;
                page = new ArrayList<>(candidates.subList(delivered, end));
                delivered = end;
                total += page.size();
                if (!listener.onPage(page)) break;
                continue;
            }

            if (!requestedCursors.add(next == null ? "" : next)) {
                exhausted = true;
                break;
            }
            requests++;
            try {
                Page page = loader.load(next);
                for (VideoItem item : page.items) {
                    if (seen.size() >= MAX_CANDIDATES) break;
                    if (item != null && seen.add(item.stableKey()) && titleFilter.accepts(item.title)) {
                        candidates.add(item);
                    }
                }
                next = page.next;
                if (next == null) exhausted = true;
            } catch (Exception error) {
                exhausted = true;
                throw error;
            }
        }
        return total;
    }

    List<VideoItem> search(int minWidth) throws Exception {
        List<VideoItem> result = new ArrayList<>();
        searchPages(minWidth, page -> {
            result.addAll(page);
            return true;
        });
        if (minWidth <= 0) return result;
        List<VideoItem> filtered = new ArrayList<>();
        for (VideoItem item : result) if (item.maxWidth >= minWidth) filtered.add(item);
        return filtered;
    }

    private int batchEnd(int minWidth) {
        if (minWidth <= 0) return Math.min(candidates.size(), TARGET);
        int end = delivered;
        int matching = matching(minWidth, delivered);
        int unknown = 0;
        while (end < candidates.size() && end - delivered < TARGET) {
            VideoItem item = candidates.get(end++);
            if (item.maxWidth >= minWidth && ++matching >= TARGET) break;
            if (item.maxWidth == 0 && !allocatedProbes.contains(item.stableKey())
                    && allocatedProbes.size() < MAX_QUALITY_PROBES && ++unknown >= 4) break;
        }
        return end;
    }

    private List<VideoItem> reserveProbes(List<VideoItem> page) {
        List<VideoItem> unknown = new ArrayList<>();
        for (VideoItem item : page) {
            if (item.maxWidth != 0 || allocatedProbes.size() >= MAX_QUALITY_PROBES) continue;
            if (allocatedProbes.add(item.stableKey())) unknown.add(item);
        }
        return unknown;
    }

    private void applyQualities() {
        for (int i = 0; i < candidates.size(); i++) {
            VideoItem known = completedProbes.get(candidates.get(i).stableKey());
            if (known != null) candidates.set(i, known);
        }
    }

    private int matching(int minWidth, int end) {
        if (minWidth <= 0) return end;
        int total = 0;
        for (int i = 0; i < end; i++) if (candidates.get(i).maxWidth >= minWidth) total++;
        return total;
    }

    static String sameOriginNext(String value, String base) {
        // Android's JSONObject.optString can stringify JSONObject.NULL as "null".
        if (value == null || value.isEmpty() || "null".equals(value)) return null;
        try {
            URI origin = URI.create(base);
            URI next = origin.resolve(value);
            return origin.getHost() != null && !next.equals(origin) && next.getFragment() == null
                    && "https".equalsIgnoreCase(next.getScheme()) && next.getUserInfo() == null
                    && origin.getHost().equalsIgnoreCase(next.getHost())
                    && (next.getPort() == -1 || next.getPort() == 443) ? next.toString() : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
