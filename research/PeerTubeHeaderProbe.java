package ru.tubetv.app;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Live, bounded A/B check of production PeerTube resolution; never downloads a whole video. */
public final class PeerTubeHeaderProbe {
    public static void main(String[] args) throws Exception {
        ScopedCookies.install();
        List<String> queries = args.length == 0
                ? Arrays.asList("Солярис", "Кин-дза-дза", "Андрей Рублев", "Отец солдата")
                : Arrays.asList(args);
        int cards = 0;
        int streams = 0;
        int unavailable = 0;
        Snapshot snapshot = new Snapshot();
        for (String query : queries) {
            PeerTubeClient.TextReader legacy = (address, limit, deadline, headers) ->
                    snapshot.fetch(address, limit, deadline,
                            headers.containsKey("Referer") ? PeerTubeClient.apiHeaders() : headers);
            List<VideoItem> beforeSearch = PeerTubeClient.search(query, 0, 320, legacy);
            List<VideoItem> items = PeerTubeClient.search(query, 0, 320, snapshot);
            for (VideoItem before : beforeSearch) {
                if (items.stream().noneMatch(item -> item.stableKey().equals(before.stableKey()))) {
                    throw new IOException("Lost search card: " + before.pageUrl);
                }
            }
            System.out.println("SEARCH " + query + ": " + beforeSearch.size() + " -> " + items.size() + " cards");
            for (VideoItem item : items) {
                cards++;
                for (boolean audio : new boolean[]{false, true}) {
                    PlaybackInfo current = PeerTubeClient.resolve(item.playUrl, audio, true, snapshot);
                    PlaybackInfo before = PeerTubeClient.resolve(item.playUrl, audio, true, legacy);
                    if (!Objects.equals(before.streamUrl, current.streamUrl)
                            || !Objects.equals(before.streamMimeType, current.streamMimeType)
                            || before.maxWidth != current.maxWidth || before.maxHeight != current.maxHeight) {
                        throw new IOException("Changed selection for " + item.pageUrl + " audio=" + audio);
                    }
                    try {
                        probeMedia(current.streamUrl, current.streamMimeType,
                                PeerTubeClient.mediaHeaders(item.pageUrl), 0);
                    } catch (IOException error) {
                        Map<String, String> previousPlaybackHeaders = new LinkedHashMap<>(
                                PeerTubeClient.mediaHeaders(item.pageUrl));
                        previousPlaybackHeaders.remove("Accept");
                        IOException previousError = null;
                        try {
                            probeMedia(before.streamUrl, before.streamMimeType, previousPlaybackHeaders, 0);
                        } catch (IOException oldError) {
                            previousError = oldError;
                        }
                        if (previousError == null || !Objects.equals(error.getMessage(), previousError.getMessage())) {
                            throw new IOException("Media regression: " + item.pageUrl + " audio=" + audio, error);
                        }
                        unavailable++;
                        System.out.println("  UNAVAILABLE WITH BOTH PROFILES " + item.pageUrl
                                + " audio=" + audio + ": " + error.getMessage());
                        continue;
                    }
                    streams++;
                    System.out.println("  " + (audio ? "AUDIO" : "VIDEO") + " OK " + item.pageUrl
                            + " -> " + URI.create(current.streamUrl).getHost());
                }
            }
        }
        if (cards == 0) throw new IOException("No live media checked");
        if (snapshot.regressions.get() != 0) throw new IOException("Manifest regressions detected");
        System.out.println("PASS cards=" + cards + " modes=" + streams
                + " manifestPairs=" + snapshot.pairs.get() + " unavailableBeforeAndAfter=" + unavailable);
    }

    private static final class Snapshot implements PeerTubeClient.TextReader {
        final Map<String, String> bodies = new ConcurrentHashMap<>();
        final AtomicInteger pairs = new AtomicInteger();
        final AtomicInteger regressions = new AtomicInteger();

        @Override public String read(String address, int limit, long deadline,
                                      Map<String, String> headers) throws Exception {
            if (headers.containsKey("Referer")) {
                String before = fetch(address, limit, deadline, PeerTubeClient.apiHeaders());
                String after;
                try {
                    after = fetch(address, limit, deadline, headers);
                } catch (IOException error) {
                    regressions.incrementAndGet();
                    throw error;
                }
                PeerTubeClient.HlsInfo oldHls = PeerTubeClient.parseHls(address, before);
                PeerTubeClient.HlsInfo newHls = PeerTubeClient.parseHls(address, after);
                if (oldHls.valid != newHls.valid || oldHls.maxWidth != newHls.maxWidth
                        || oldHls.maxHeight != newHls.maxHeight || oldHls.muxed != newHls.muxed
                        || !Objects.equals(oldHls.separateAudioUrl, newHls.separateAudioUrl)
                        || !Objects.equals(oldHls.lowestMuxedUrl, newHls.lowestMuxedUrl)) {
                    regressions.incrementAndGet();
                    throw new IOException("Changed HLS variants: " + address);
                }
                pairs.incrementAndGet();
                System.out.println("MANIFEST A/B OK " + URI.create(address).getHost());
                return after;
            }
            return fetch(address, limit, deadline, headers);
        }

        String fetch(String address, int limit, long deadline, Map<String, String> headers) throws IOException {
            String key = headers.toString() + "\n" + address;
            String cached = bodies.get(key);
            if (cached != null) return cached;
            String body = PeerTubeClient.getText(address, limit, deadline, headers);
            bodies.put(key, body);
            return body;
        }
    }

    private static void probeMedia(String address, String mime, Map<String, String> headers,
                                    int depth) throws Exception {
        if (depth > 3) throw new IOException("Too many nested HLS playlists");
        if (!"application/x-mpegURL".equals(mime) && !address.contains(".m3u8")) {
            readPrefix(address, headers);
            return;
        }
        String manifest = PeerTubeClient.getText(address, 1024 * 1024, Long.MAX_VALUE, headers);
        if (!manifest.trim().startsWith("#EXTM3U")) throw new IOException("Not HLS: " + address);
        boolean master = manifest.contains("#EXT-X-STREAM-INF:");
        String first = null;
        for (String raw : manifest.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-MAP:")) {
                Matcher uri = Pattern.compile("URI=\"([^\"]+)\"").matcher(line);
                if (uri.find()) readPrefix(URI.create(address).resolve(uri.group(1)).toString(), headers);
            }
            if (first == null && !line.isEmpty() && !line.startsWith("#")) first = line;
        }
        if (first == null) throw new IOException("HLS has no media: " + address);
        String child = URI.create(address).resolve(first).toString();
        if (master) probeMedia(child, "application/x-mpegURL", headers, depth + 1);
        else readPrefix(child, headers);
    }

    private static void readPrefix(String address, Map<String, String> headers) throws IOException {
        ScopedCookies.with(ScopedCookies.NONE, () -> {
            HttpURLConnection connection = (HttpURLConnection) URI.create(address).toURL().openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            connection.setRequestProperty("Range", "bytes=0-32767");
            try {
                int status = connection.getResponseCode();
                if (status != 200 && status != 206) throw new IOException("Media HTTP " + status + " " + address);
                String type = connection.getContentType();
                if (type != null && (type.contains("text/html") || type.contains("application/json"))) {
                    throw new IOException("Not media: " + type);
                }
                int count = 0;
                byte[] buffer = new byte[8192];
                try (InputStream input = connection.getInputStream()) {
                    while (count < 32768) {
                        int read = input.read(buffer, 0, Math.min(buffer.length, 32768 - count));
                        if (read < 0) break;
                        count += read;
                    }
                }
                if (count == 0) throw new IOException("Empty media: " + address);
                System.out.println("  MEDIA HTTP " + status + " " + count + " bytes " + type);
                return null;
            } finally {
                connection.disconnect();
            }
        });
    }

    private PeerTubeHeaderProbe() { }
}
