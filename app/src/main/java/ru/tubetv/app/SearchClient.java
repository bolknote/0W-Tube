package ru.tubetv.app;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class SearchClient {
    private static final int LIMIT = 12;
    private static final int TIMEOUT_MS = 7000;

    List<VideoItem> searchRutube(String query, int minWidth) throws Exception {
        String url = "https://rutube.ru/api/search/video/?query="
                + Uri.encode(query) + "&page=1";
        JSONObject root = getJson(url);
        JSONArray items = root.optJSONArray("results");
        List<VideoItem> result = new ArrayList<>();
        if (items == null) return result;
        for (int i = 0; i < Math.min(items.length(), LIMIT); i++) {
            JSONObject item = items.getJSONObject(i);
            String id = item.optString("id");
            String page = item.optString("video_url", "https://rutube.ru/video/" + id + "/");
            String embed = item.optString("embed_url", "https://rutube.ru/play/embed/" + id);
            int duration = item.optInt("duration");
            result.add(new VideoItem("RUTUBE", item.optString("title", "Без названия"),
                    "", item.optString("thumbnail_url"),
                    embed, page, duration * 1000L));
        }
        if (minWidth <= 0 || result.isEmpty()) return result;
        return filterByQuality(result, minWidth);
    }

    List<VideoItem> searchVk(String query, int minWidth) throws Exception {
        return searchVk(query, minWidth, 480);
    }

    List<VideoItem> searchVk(String query, int minWidth, int thumbnailWidth) throws Exception {
        return new VkWebClient().search(query, minWidth, thumbnailWidth);
    }

    List<VideoItem> searchDzen(String query, int minWidth) throws Exception {
        return new DzenClient().search(query, minWidth);
    }

    static List<VideoItem> filterByQuality(List<VideoItem> candidates, int minWidth) throws Exception {
        return filterByQuality(candidates, minWidth, 4);
    }

    static List<VideoItem> filterByQuality(List<VideoItem> candidates, int minWidth,
                                           int maxParallelRequests) throws Exception {
        List<VideoItem> inspected = inspectQualities(candidates, maxParallelRequests);
        List<VideoItem> filtered = new ArrayList<>();
        for (VideoItem item : inspected) if (item.maxWidth >= minWidth) filtered.add(item);
        return filtered;
    }

    static List<VideoItem> inspectQualities(List<VideoItem> candidates, int maxParallelRequests) {
        if (candidates.isEmpty()) return new ArrayList<>();
        int workers = Math.max(1, Math.min(maxParallelRequests, candidates.size()));
        ExecutorService probes = Executors.newFixedThreadPool(workers);
        try {
            List<Future<VideoItem>> futures = new ArrayList<>();
            for (VideoItem item : candidates) {
                futures.add(probes.submit(() -> {
                    PlaybackInfo info = new StreamResolver().inspect(item.playUrl);
                    return item.withQuality(info.maxWidth, info.maxHeight);
                }));
            }
            List<VideoItem> inspected = new ArrayList<>();
            for (Future<VideoItem> future : futures) {
                if (Thread.currentThread().isInterrupted()) break;
                try {
                    VideoItem item = future.get();
                    if (item != null) inspected.add(item);
                } catch (Exception ignored) { }
            }
            return inspected;
        } finally {
            probes.shutdownNow();
        }
    }

    private static JSONObject getJson(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "0W-Tube/0.5.11 AndroidTV");
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
            ByteArrayOutputStream output = new ByteArrayOutputStream(32 * 1024);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
        } finally {
            connection.disconnect();
        }
    }

}
