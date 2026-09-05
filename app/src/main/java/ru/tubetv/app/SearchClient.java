package ru.tubetv.app;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

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
            embed = copyQueryParameter(page, embed, "p");
            int duration = item.optInt("duration");
            result.add(new VideoItem("RUTUBE", item.optString("title", "Без названия"),
                    "", item.optString("thumbnail_url"),
                    embed, page, duration * 1000L));
        }
        if (minWidth <= 0 || result.isEmpty()) return result;
        return filterByQuality(result, minWidth);
    }

    int searchVkPages(String query, int minWidth, int thumbnailWidth,
                      VkWebClient.PageListener listener) throws Exception {
        return new VkWebClient().searchPages(query, minWidth, thumbnailWidth, listener);
    }

    List<VideoItem> searchOk(String query, int minWidth) throws Exception {
        return OkClient.search(query, minWidth);
    }

    List<VideoItem> searchPeerTube(String query, int minWidth, int thumbnailWidth) throws Exception {
        return PeerTubeClient.search(query, minWidth, thumbnailWidth);
    }

    int searchDzenPages(String query, DzenClient.PageListener listener) throws Exception {
        return new DzenClient().searchPages(query, listener);
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

    private static String copyQueryParameter(String source, String target, String name) {
        if (source == null || target == null) return target;
        try {
            String value = Uri.parse(source).getQueryParameter(name);
            if (value == null || value.isEmpty()
                    || Uri.parse(target).getQueryParameter(name) != null) return target;
            return Uri.parse(target).buildUpon().appendQueryParameter(name, value).build().toString();
        } catch (Exception ignored) {
            return target;
        }
    }

    static List<VideoItem> inspectQualities(List<VideoItem> candidates, int maxParallelRequests) {
        return ParallelTasks.map(candidates, maxParallelRequests, item -> {
            MediaCapabilities info = new StreamResolver().inspect(item.playUrl);
            return item.withQuality(info.maxWidth, info.maxHeight);
        });
    }

    private static JSONObject getJson(String address) throws Exception {
        BoundedHttpReader.checkInterrupted();
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "0W-Tube/" + BuildConfig.VERSION_NAME + " Android");
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            return new JSONObject(BoundedHttpReader.readText(connection.getInputStream(),
                    BoundedHttpReader.JSON_LIMIT));
        } finally {
            connection.disconnect();
        }
    }

}
