package ru.tubetv.netprobe;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class NetworkProbeActivity extends Activity {
    private static final int PARALLEL_REQUESTS = 4;
    private static final int TEXT_LIMIT = 1024 * 1024;
    private static final int THUMBNAIL_LIMIT = 2 * 1024 * 1024;
    private static final int VIDEO_LIMIT = 256 * 1024;
    private static final String USER_AGENT = "0W-Network-Probe/1.2 Android";
    private static final String RUTUBE_REFERER = "https://rutube.ru/";
    private static final String SEARCH_URL = "https://rutube.ru/api/search/video/?query="
            + Uri.encode("призрак") + "&page=1";

    private final StringBuilder report = new StringBuilder(8192);
    private TextView status;
    private TextView output;
    private Button start;
    private volatile boolean running;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(createContent());
    }

    private View createContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 18, 24));
        final int horizontal = dp(18);
        final int vertical = dp(14);
        root.setPadding(horizontal, vertical, horizontal, vertical);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets safe = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(horizontal + safe.left, vertical + safe.top,
                    horizontal + safe.right, vertical + safe.bottom);
            return windowInsets;
        });

        TextView title = text("0W NETWORK PROBE · REAL MEDIA", 21, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(38)));

        TextView description = text(
                "Находит реальный ролик RUTUBE и трижды сравнивает поиск, разные обложки, "
                        + "HLS и разные 1080p-сегменты.",
                14, Color.rgb(190, 196, 210));
        root.addView(description, new LinearLayout.LayoutParams(-1, -2));

        start = new Button(this);
        start.setText("ЗАПУСТИТЬ ТЕСТ");
        start.setTextColor(Color.WHITE);
        start.setBackgroundColor(Color.rgb(124, 92, 252));
        start.setOnClickListener(ignored -> startProbe());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, dp(48));
        buttonParams.setMargins(0, dp(12), 0, dp(8));
        root.addView(start, buttonParams);

        status = text("Готово к запуску", 14, Color.rgb(154, 134, 255));
        status.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(32)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        output = text("Запускайте по Wi-Fi, без VPN. Отчёт можно выделить и скопировать.\n",
                13, Color.rgb(228, 231, 239));
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        scroll.addView(output, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        ViewCompat.requestApplyInsets(root);
        return root;
    }

    private void startProbe() {
        if (running) return;
        running = true;
        start.setEnabled(false);
        report.setLength(0);
        report.append("DEVICE: ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append('\n');
        report.append("ANDROID: ").append(Build.VERSION.RELEASE).append(" · API ")
                .append(Build.VERSION.SDK_INT).append('\n');
        report.append("QUERY: призрак\n");
        report.append("PARALLEL ASSETS: ").append(PARALLEL_REQUESTS).append("\n\n");
        publish();
        new Thread(this::runProbe, "network-probe").start();
    }

    private void runProbe() {
        OkLoader http1 = null;
        OkLoader http2 = null;
        try {
            setStatus("Ищу тестовый ролик и поток…");
            long discoveryStarted = SystemClock.elapsedRealtimeNanos();
            Workload workload = discoverRutubeWorkload();
            long discoveryMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - discoveryStarted);
            report.append("VIDEO: ").append(workload.title).append('\n');
            report.append("DISCOVERY: ").append(discoveryMs).append(" ms\n");
            report.append("QUALITY: ").append(workload.quality).append('\n');
            report.append("THUMBNAILS: ").append(workload.thumbnailCount).append(" distinct\n");
            report.append("SEGMENTS: ").append(workload.segmentCount).append(" distinct\n");
            report.append("THUMB HOST: ").append(host(workload.thumbnail.urls.get(0))).append('\n');
            report.append("STREAM HOST: ").append(host(workload.segment.urls.get(0))).append("\n\n");
            publish();

            PlatformLoader platform = new PlatformLoader();
            http1 = new OkLoader(Collections.singletonList(Protocol.HTTP_1_1));
            http2 = new OkLoader(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1));
            for (int i = 0; i < workload.targets.size(); i++) {
                Target target = workload.targets.get(i);
                setStatus("Проверяю " + target.name + " · " + (i + 1) + "/" + workload.targets.size());
                report.append("=== ").append(target.name).append(" ===\n");
                ModeStats systemStats = new ModeStats();
                ModeStats h1Stats = new ModeStats();
                ModeStats h2Stats = new ModeStats();
                for (int cycle = 0; cycle < 3; cycle++) {
                    systemStats.add(runTarget(platform, target, "SYSTEM-" + cycle, null));
                    if ((cycle & 1) == 0) {
                        h1Stats.add(runTarget(http1, target, "H1-" + cycle, http1.counters));
                        h2Stats.add(runTarget(http2, target, "H2-" + cycle, http2.counters));
                    } else {
                        h2Stats.add(runTarget(http2, target, "H2-" + cycle, http2.counters));
                        h1Stats.add(runTarget(http1, target, "H1-" + cycle, http1.counters));
                    }
                }
                appendStats("SYSTEM", systemStats);
                appendStats("OKHTTP H1", h1Stats);
                appendStats("OKHTTP H2", h2Stats);
                appendVerdict(h1Stats, h2Stats);
                report.append('\n');
                publish();
            }
            report.append("END OF REAL-MEDIA TEST\n");
            setStatus("Готово");
        } catch (Throwable error) {
            report.append("\nTEST FAILED: ").append(error).append('\n');
            setStatus("Ошибка теста");
        } finally {
            if (http1 != null) http1.close();
            if (http2 != null) http2.close();
            publish();
            runOnUiThread(() -> {
                running = false;
                start.setEnabled(true);
                start.setText("ПОВТОРИТЬ ТЕСТ");
            });
        }
    }

    private Workload discoverRutubeWorkload() throws Exception {
        JSONObject search = new JSONObject(getText(SEARCH_URL, null, TEXT_LIMIT));
        JSONArray results = search.optJSONArray("results");
        if (results == null || results.length() == 0) throw new Exception("RUTUBE search returned no videos");
        LinkedHashSet<String> thumbnails = new LinkedHashSet<>();
        for (int i = 0; i < results.length() && thumbnails.size() < PARALLEL_REQUESTS; i++) {
            String candidate = results.optJSONObject(i) == null ? ""
                    : results.optJSONObject(i).optString("thumbnail_url");
            if (candidate.startsWith("http")) thumbnails.add(candidate);
        }
        if (thumbnails.isEmpty()) throw new Exception("RUTUBE result has no thumbnails");

        JSONObject item = null;
        Playlist playlist = null;
        String page = null;
        int inspected = Math.min(results.length(), 8);
        for (int i = 0; i < inspected; i++) {
            JSONObject candidateItem = results.optJSONObject(i);
            if (candidateItem == null) continue;
            String id = candidateItem.optString("id");
            if (id.isEmpty()) continue;
            String candidatePage = "https://rutube.ru/video/" + id + "/";
            setStatus("Ищу поток до 1080p · " + (i + 1) + "/" + inspected);
            try {
                Playlist candidate = findBestRutubePlaylist(id, candidatePage);
                int candidateHeight = candidate.height > 0 ? candidate.height : 1;
                int bestHeight = playlist == null ? 0 : Math.max(1, playlist.height);
                if (playlist == null || candidateHeight > bestHeight) {
                    item = candidateItem;
                    playlist = candidate;
                    page = candidatePage;
                }
                if (candidate.height == 1080) break;
            } catch (Exception ignored) {
                // Try the next public search result.
            }
        }
        if (item == null || playlist == null || page == null) {
            throw new Exception("RUTUBE returned no usable HLS stream");
        }

        List<String> thumbnailUrls = new ArrayList<>(thumbnails);
        List<String> segmentUrls = playlist.segments;
        Target searchTarget = Target.single("ПОИСК · ОДИН ЗАПРОС", SEARCH_URL,
                TEXT_LIMIT, null, null, true);
        Target thumbnailTarget = new Target("РАЗНЫЕ ОБЛОЖКИ · ПАРАЛЛЕЛЬНО", thumbnailUrls,
                THUMBNAIL_LIMIT, RUTUBE_REFERER, null, true, false);
        Target manifestTarget = Target.single("HLS-МАНИФЕСТ · ОДИН ЗАПРОС", playlist.url,
                TEXT_LIMIT, page, null, false);
        Target sequentialSegments = new Target("РАЗНЫЕ СЕГМЕНТЫ · ПОСЛЕДОВАТЕЛЬНО", segmentUrls,
                VIDEO_LIMIT, page, "bytes=0-" + (VIDEO_LIMIT - 1), false, false);
        Target parallelSegments = new Target("РАЗНЫЕ СЕГМЕНТЫ · ПАРАЛЛЕЛЬНО", segmentUrls,
                VIDEO_LIMIT, page, "bytes=0-" + (VIDEO_LIMIT - 1), true, false);
        String quality = playlist.height > 0 ? playlist.height + "p" : "unknown";
        return new Workload(item.optString("title", "Без названия"), thumbnailTarget, parallelSegments,
                quality, thumbnailUrls.size(), segmentUrls.size(), Arrays.asList(searchTarget,
                thumbnailTarget, manifestTarget, sequentialSegments, parallelSegments));
    }

    private Playlist findBestRutubePlaylist(String id, String page) throws Exception {
        String optionsUrl = "https://rutube.ru/api/play/options/" + id + "/?format=json";
        JSONObject options = new JSONObject(getText(optionsUrl, page, TEXT_LIMIT));
        JSONObject balancer = options.optJSONObject("video_balancer");
        if (balancer == null) throw new Exception("RUTUBE returned no video_balancer");
        LinkedHashSet<String> hlsUrls = new LinkedHashSet<>();
        for (Iterator<String> keys = balancer.keys(); keys.hasNext();) {
            String value = balancer.optString(keys.next());
            if (value.startsWith("http") && (value.contains(".m3u8") || value.contains("ct=8"))) {
                hlsUrls.add(value);
            }
        }
        Playlist best = null;
        for (String hls : hlsUrls) {
            try {
                Playlist candidate = findMediaPlaylist(hls, page);
                if (best == null || candidate.height > best.height) best = candidate;
                if (candidate.height == 1080) break;
            } catch (Exception ignored) { }
        }
        if (best == null) throw new Exception("RUTUBE returned no HLS stream");
        return best;
    }

    private Playlist findMediaPlaylist(String initialUrl, String referer) throws Exception {
        String currentUrl = initialUrl;
        int selectedHeight = 0;
        for (int depth = 0; depth < 3; depth++) {
            String body = getText(currentUrl, referer, TEXT_LIMIT);
            Variant variant = bestVariant(body, currentUrl);
            if (variant != null) {
                currentUrl = variant.url;
                if (variant.height > 0) selectedHeight = variant.height;
                continue;
            }
            List<String> segments = firstSegments(body, currentUrl, PARALLEL_REQUESTS);
            if (!segments.isEmpty()) return new Playlist(currentUrl, selectedHeight, segments);
            throw new Exception("HLS playlist has no media segment");
        }
        throw new Exception("Too many nested HLS playlists");
    }

    private static Variant bestVariant(String manifest, String baseUrl) throws Exception {
        String[] lines = manifest.split("\\r?\\n");
        Variant best = null;
        Variant smallestAbove = null;
        Variant unknown = null;
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue;
            int height = resolutionHeight(lines[i]);
            for (int next = i + 1; next < lines.length; next++) {
                String value = lines[next].trim();
                if (!value.isEmpty() && !value.startsWith("#")) {
                    Variant candidate = new Variant(new URL(new URL(baseUrl), value).toString(), height);
                    if (height > 0 && height <= 1080 && (best == null || height > best.height)) {
                        best = candidate;
                    } else if (height > 1080
                            && (smallestAbove == null || height < smallestAbove.height)) {
                        smallestAbove = candidate;
                    } else if (height == 0 && unknown == null) {
                        unknown = candidate;
                    }
                    break;
                }
            }
        }
        return best != null ? best : smallestAbove != null ? smallestAbove : unknown;
    }

    private static int resolutionHeight(String streamInfo) {
        int marker = streamInfo.indexOf("RESOLUTION=");
        if (marker < 0) return 0;
        int x = streamInfo.indexOf('x', marker + 11);
        if (x < 0) return 0;
        int end = x + 1;
        while (end < streamInfo.length() && Character.isDigit(streamInfo.charAt(end))) end++;
        try { return Integer.parseInt(streamInfo.substring(x + 1, end)); }
        catch (Exception ignored) { return 0; }
    }

    private static List<String> firstSegments(String manifest, String baseUrl, int limit) throws Exception {
        String[] lines = manifest.split("\\r?\\n");
        List<String> segments = new ArrayList<>();
        boolean afterDuration = false;
        for (String line : lines) {
            String value = line.trim();
            if (value.startsWith("#EXTINF")) {
                afterDuration = true;
                continue;
            }
            if (afterDuration && !value.isEmpty() && !value.startsWith("#")) {
                segments.add(new URL(new URL(baseUrl), value).toString());
                afterDuration = false;
                if (segments.size() >= limit) break;
            }
        }
        return segments;
    }

    private Batch runTarget(Loader loader, Target target, String label, Counters counters) throws Exception {
        long nonce = System.nanoTime();
        int connectsBefore = counters == null ? 0 : counters.connects.get();
        int tlsBefore = counters == null ? 0 : counters.tls.get();
        long started = SystemClock.elapsedRealtimeNanos();
        List<Sample> samples = new ArrayList<>();
        if (target.parallel && target.urls.size() > 1) {
            ExecutorService workers = Executors.newFixedThreadPool(target.urls.size());
            List<Future<Sample>> futures = new ArrayList<>();
            try {
                for (int i = 0; i < target.urls.size(); i++) {
                    final int requestIndex = i;
                    futures.add(workers.submit((Callable<Sample>) () -> loader.load(
                            target.forRequest(label, nonce, requestIndex))));
                }
                for (Future<Sample> future : futures) samples.add(future.get());
            } finally {
                workers.shutdownNow();
            }
        } else {
            for (int i = 0; i < target.urls.size(); i++) {
                samples.add(loader.load(target.forRequest(label, nonce, i)));
            }
        }
        long elapsedMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - started);
        int connects = counters == null ? 0 : counters.connects.get() - connectsBefore;
        int tls = counters == null ? 0 : counters.tls.get() - tlsBefore;
        return Batch.from(samples, elapsedMs, connects, tls);
    }

    private void appendStats(String label, ModeStats stats) {
        report.append(String.format(Locale.ROOT,
                "%-11s median=%4d ms | runs=%s | ok=%d/%d | %s | %d KiB/run",
                label, stats.medianMs(), stats.timesLabel(), stats.success(), stats.totalRequests(),
                stats.protocolLabel(), stats.medianBytes() / 1024));
        if (stats.connects() > 0 || stats.tls() > 0) {
            report.append(" | connect=").append(stats.connects()).append(" tls=").append(stats.tls());
        }
        if (!stats.errors().isEmpty()) report.append(" | ").append(stats.errors());
        report.append('\n');
        publish();
    }

    private void appendVerdict(ModeStats h1, ModeStats h2) {
        if (!h2.protocols().contains("h2")) {
            report.append("RESULT: этот ресурс не согласовал HTTP/2.\n");
            return;
        }
        long baseline = Math.max(1L, h1.medianMs());
        long percent = (baseline - h2.medianMs()) * 100L / baseline;
        if (percent > 0) {
            report.append("RESULT: HTTP/2 быстрее на ").append(percent).append("%.\n");
        } else if (percent < 0) {
            report.append("RESULT: HTTP/2 медленнее на ").append(-percent).append("%.\n");
        } else {
            report.append("RESULT: одинаковое время.\n");
        }
    }

    private static String getText(String address, String referer, int limit) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(12_000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/json,text/plain,*/*");
        if (referer != null) connection.setRequestProperty("Referer", referer);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code + " for " + host(address));
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 64 * 1024));
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                while (total < limit) {
                    int read = input.read(buffer, 0, Math.min(buffer.length, limit - total));
                    if (read < 0) break;
                    output.write(buffer, 0, read);
                    total += read;
                }
            }
            return output.toString(StandardCharsets.UTF_8.name());
        } finally {
            connection.disconnect();
        }
    }

    private void publish() {
        String value = report.toString();
        runOnUiThread(() -> output.setText(value));
    }

    private void setStatus(String value) {
        runOnUiThread(() -> status.setText(value));
    }

    private interface Loader {
        Sample load(RequestTarget request);
    }

    private static final class PlatformLoader implements Loader {
        @Override public Sample load(RequestTarget request) {
            long started = SystemClock.elapsedRealtimeNanos();
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(request.url).openConnection();
                connection.setConnectTimeout(12_000);
                connection.setReadTimeout(15_000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Cache-Control", "no-cache, no-store");
                connection.setRequestProperty("Accept-Encoding", "identity");
                if (request.referer != null) connection.setRequestProperty("Referer", request.referer);
                if (request.range != null) connection.setRequestProperty("Range", request.range);
                int code = connection.getResponseCode();
                String statusLine = connection.getHeaderField(null);
                InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
                int bytes = readLimited(stream, request.limit);
                return Sample.ok(nanosToMs(SystemClock.elapsedRealtimeNanos() - started),
                        bytes, code, statusProtocol(statusLine));
            } catch (Throwable error) {
                return Sample.failed(nanosToMs(SystemClock.elapsedRealtimeNanos() - started), error);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
    }

    private static final class OkLoader implements Loader {
        final Counters counters = new Counters();
        final OkHttpClient client;

        OkLoader(List<Protocol> protocols) {
            client = new OkHttpClient.Builder()
                    .protocols(protocols)
                    .connectTimeout(12, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .callTimeout(20, TimeUnit.SECONDS)
                    .eventListenerFactory(counters.factory())
                    .build();
        }

        @Override public Sample load(RequestTarget requestTarget) {
            long started = SystemClock.elapsedRealtimeNanos();
            Request.Builder request = new Request.Builder()
                    .url(requestTarget.url)
                    .header("User-Agent", USER_AGENT)
                    .header("Cache-Control", "no-cache, no-store")
                    .header("Accept-Encoding", "identity");
            if (requestTarget.referer != null) request.header("Referer", requestTarget.referer);
            if (requestTarget.range != null) request.header("Range", requestTarget.range);
            try (Response response = client.newCall(request.build()).execute()) {
                ResponseBody body = response.body();
                int bytes = body == null ? 0 : readLimited(body.byteStream(), requestTarget.limit);
                return Sample.ok(nanosToMs(SystemClock.elapsedRealtimeNanos() - started),
                        bytes, response.code(), response.protocol().toString());
            } catch (Throwable error) {
                return Sample.failed(nanosToMs(SystemClock.elapsedRealtimeNanos() - started), error);
            }
        }

        void close() {
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdown();
        }
    }

    private static final class Counters {
        final AtomicInteger connects = new AtomicInteger();
        final AtomicInteger tls = new AtomicInteger();

        EventListener.Factory factory() {
            return call -> new EventListener() {
                @Override public void connectStart(Call call, InetSocketAddress address, Proxy proxy) {
                    connects.incrementAndGet();
                }

                @Override public void secureConnectStart(Call call) {
                    tls.incrementAndGet();
                }
            };
        }
    }

    private static final class Workload {
        final String title;
        final Target thumbnail;
        final Target segment;
        final String quality;
        final int thumbnailCount;
        final int segmentCount;
        final List<Target> targets;

        Workload(String title, Target thumbnail, Target segment, String quality,
                 int thumbnailCount, int segmentCount, List<Target> targets) {
            this.title = title;
            this.thumbnail = thumbnail;
            this.segment = segment;
            this.quality = quality;
            this.thumbnailCount = thumbnailCount;
            this.segmentCount = segmentCount;
            this.targets = targets;
        }
    }

    private static final class Playlist {
        final String url;
        final int height;
        final List<String> segments;

        Playlist(String url, int height, List<String> segments) {
            this.url = url;
            this.height = height;
            this.segments = segments;
        }
    }

    private static final class Variant {
        final String url;
        final int height;

        Variant(String url, int height) {
            this.url = url;
            this.height = height;
        }
    }

    private static final class Target {
        final String name;
        final List<String> urls;
        final int limit;
        final String referer;
        final String range;
        final boolean parallel;
        final boolean uniqueQuery;

        Target(String name, List<String> urls, int limit, String referer, String range,
               boolean parallel, boolean uniqueQuery) {
            this.name = name;
            this.urls = urls;
            this.limit = limit;
            this.referer = referer;
            this.range = range;
            this.parallel = parallel;
            this.uniqueQuery = uniqueQuery;
        }

        static Target single(String name, String url, int limit, String referer,
                             String range, boolean uniqueQuery) {
            return new Target(name, Collections.singletonList(url), limit, referer,
                    range, false, uniqueQuery);
        }

        RequestTarget forRequest(String label, long nonce, int index) {
            String address = urls.get(index);
            if (uniqueQuery) {
                address += (address.contains("?") ? '&' : '?')
                        + "ow_probe=" + nonce + '-' + label + '-' + index;
            }
            return new RequestTarget(address, limit, referer, range);
        }
    }

    private static final class RequestTarget {
        final String url;
        final int limit;
        final String referer;
        final String range;

        RequestTarget(String url, int limit, String referer, String range) {
            this.url = url;
            this.limit = limit;
            this.referer = referer;
            this.range = range;
        }
    }

    private static final class Sample {
        final long elapsedMs;
        final int bytes;
        final int code;
        final String protocol;
        final String error;

        private Sample(long elapsedMs, int bytes, int code, String protocol, String error) {
            this.elapsedMs = elapsedMs;
            this.bytes = bytes;
            this.code = code;
            this.protocol = protocol;
            this.error = error;
        }

        static Sample ok(long elapsedMs, int bytes, int code, String protocol) {
            return new Sample(elapsedMs, bytes, code, protocol, null);
        }

        static Sample failed(long elapsedMs, Throwable error) {
            String message = error.getClass().getSimpleName();
            if (error.getMessage() != null) message += ": " + error.getMessage();
            return new Sample(elapsedMs, 0, -1, "?", message);
        }

        boolean successful() { return code >= 200 && code < 400 && error == null; }
    }

    private static final class Batch {
        final long elapsedMs;
        final int success;
        final int bytes;
        final int requests;
        final int connects;
        final int tls;
        final Set<String> protocols;
        final Set<String> errors;

        Batch(long elapsedMs, int success, int bytes, int requests, int connects, int tls,
              Set<String> protocols, Set<String> errors) {
            this.elapsedMs = elapsedMs;
            this.success = success;
            this.bytes = bytes;
            this.requests = requests;
            this.connects = connects;
            this.tls = tls;
            this.protocols = protocols;
            this.errors = errors;
        }

        static Batch from(List<Sample> samples, long elapsedMs, int connects, int tls) {
            int success = 0;
            int bytes = 0;
            Set<String> protocols = new LinkedHashSet<>();
            Set<String> errors = new LinkedHashSet<>();
            for (Sample sample : samples) {
                if (sample.successful()) success++;
                bytes += sample.bytes;
                protocols.add(sample.protocol);
                if (sample.error != null) errors.add(sample.error);
                else if (!sample.successful()) errors.add("HTTP " + sample.code);
            }
            return new Batch(elapsedMs, success, bytes, samples.size(), connects, tls,
                    protocols, errors);
        }

        String protocolLabel() {
            if (protocols.isEmpty()) return "protocol=?";
            return "protocol=" + android.text.TextUtils.join(",", protocols);
        }
    }

    private static final class ModeStats {
        final List<Batch> runs = new ArrayList<>();

        void add(Batch batch) { runs.add(batch); }

        long medianMs() {
            List<Long> values = new ArrayList<>();
            for (Batch run : runs) values.add(run.elapsedMs);
            Collections.sort(values);
            return values.get(values.size() / 2);
        }

        int medianBytes() {
            List<Integer> values = new ArrayList<>();
            for (Batch run : runs) values.add(run.bytes);
            Collections.sort(values);
            return values.get(values.size() / 2);
        }

        String timesLabel() {
            List<String> values = new ArrayList<>();
            for (Batch run : runs) values.add(String.valueOf(run.elapsedMs));
            return "[" + android.text.TextUtils.join(",", values) + "]";
        }

        int success() {
            int total = 0;
            for (Batch run : runs) total += run.success;
            return total;
        }

        int totalRequests() {
            int total = 0;
            for (Batch run : runs) total += run.requests;
            return total;
        }

        int connects() {
            int total = 0;
            for (Batch run : runs) total += run.connects;
            return total;
        }

        int tls() {
            int total = 0;
            for (Batch run : runs) total += run.tls;
            return total;
        }

        Set<String> protocols() {
            Set<String> values = new LinkedHashSet<>();
            for (Batch run : runs) values.addAll(run.protocols);
            return values;
        }

        Set<String> errors() {
            Set<String> values = new LinkedHashSet<>();
            for (Batch run : runs) values.addAll(run.errors);
            return values;
        }

        String protocolLabel() {
            Set<String> values = protocols();
            return values.isEmpty() ? "protocol=?"
                    : "protocol=" + android.text.TextUtils.join(",", values);
        }
    }

    private static int readLimited(InputStream stream, int limit) throws Exception {
        if (stream == null) return 0;
        try (InputStream input = stream) {
            byte[] buffer = new byte[8192];
            int total = 0;
            while (total < limit) {
                int read = input.read(buffer, 0, Math.min(buffer.length, limit - total));
                if (read < 0) break;
                total += read;
            }
            return total;
        }
    }

    private static String statusProtocol(String statusLine) {
        if (statusLine == null || statusLine.trim().isEmpty()) return "platform/?";
        int space = statusLine.indexOf(' ');
        return space > 0 ? statusLine.substring(0, space) : statusLine;
    }

    private static String host(String address) {
        try { return new URL(address).getHost(); }
        catch (Exception ignored) { return "?"; }
    }

    private static long nanosToMs(long nanos) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(nanos));
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
