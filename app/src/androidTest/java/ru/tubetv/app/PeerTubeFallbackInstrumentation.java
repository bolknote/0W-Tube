package ru.tubetv.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.OptIn;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.ExoPlayer;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** No extra test dependency; runs actual Media3 requests on an Android emulator/device. */
@OptIn(markerClass = UnstableApi.class)
public final class PeerTubeFallbackInstrumentation extends Instrumentation {
    private static final String PRIMARY = "https://files.example/audio.mp4";
    private static final String DOWNLOAD = "https://files.example/download/audio.mp4";
    private boolean live;
    private final StringBuilder report = new StringBuilder();

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        live = arguments != null && "true".equals(arguments.getString("live"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            retriesMissingFile(404);
            retriesMissingFile(410);
            rejectsOtherFailures();
            doesNotRetryUnrelatedUris();
            doesNotLoopWhenDownloadFails();
            sharesSuccessfulTargetAcrossSeekRequests();
            if (live) liveMedia();
            result.putString("stream", report + "PASS\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", report + "FAIL: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private static DataSpec request(String address, long position) {
        return new DataSpec.Builder().setUri(address).setPosition(position).setLength(4096)
                .setUriPositionOffset(17).setKey("same-cache-key").setCustomData("same-custom-data")
                .setFlags(DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN)
                .setHttpRequestHeaders(Collections.singletonMap("X-Test", "retained")).build();
    }

    private void retriesMissingFile(int status) throws Exception {
        Fake upstream = new Fake(status, 0);
        FileFallbackDataSource.Target target = new FileFallbackDataSource.Target(PRIMARY, DOWNLOAD);
        DataSource source = new FileFallbackDataSource(upstream, target);
        DataSpec original = request(PRIMARY, 1_048_576);
        check(source.open(original) == 4096, "fallback length");
        check(upstream.requests.size() == 2 && upstream.closed == 1, "one retry after close");
        DataSpec retry = upstream.requests.get(1);
        check(retry.uri.toString().equals(DOWNLOAD), "API download URL");
        check(retry.position == original.position && retry.length == original.length
                && retry.uriPositionOffset == original.uriPositionOffset && retry.flags == original.flags
                && retry.key.equals(original.key) && retry.customData == original.customData
                && retry.httpMethod == original.httpMethod && retry.httpBody == original.httpBody
                && retry.httpRequestHeaders.equals(original.httpRequestHeaders), "preserved DataSpec");
        check(source.getUri().toString().equals(DOWNLOAD), "effective URI");
        check(source.read(new byte[4], 0, 4) == 4, "read delegated");
        source.close();
        report.append(status).append(": fallback + preserved Range/headers OK\n");
    }

    private void rejectsOtherFailures() throws Exception {
        for (int status : new int[]{400, 401, 403, 405, 408, 416, 429, 500, 503, -1}) {
            Fake upstream = new Fake(status, 0);
            DataSource source = new FileFallbackDataSource(upstream, new FileFallbackDataSource.Target(PRIMARY, DOWNLOAD));
            try {
                source.open(request(PRIMARY, 0));
                throw new AssertionError("unexpected success " + status);
            } catch (IOException expected) {
                check(upstream.requests.size() == 1 && upstream.closed == 0, "no fallback " + status);
            } finally { source.close(); }
        }
        report.append("Other HTTP/network failures: no fallback OK\n");
    }

    private void doesNotRetryUnrelatedUris() throws Exception {
        Fake upstream = new Fake(404, 0);
        DataSource source = new FileFallbackDataSource(upstream, new FileFallbackDataSource.Target(PRIMARY, DOWNLOAD));
        try {
            source.open(request("https://files.example/unrelated-segment.mp4", 0));
            throw new AssertionError("unexpected success");
        } catch (HttpDataSource.InvalidResponseCodeException expected) {
            check(upstream.requests.size() == 1, "unrelated HLS segment not replaced");
        } finally { source.close(); }
    }

    private void doesNotLoopWhenDownloadFails() throws Exception {
        Fake upstream = new Fake(404, 410);
        FileFallbackDataSource.Target target = new FileFallbackDataSource.Target(PRIMARY, DOWNLOAD);
        DataSource source = new FileFallbackDataSource(upstream, target);
        try {
            source.open(request(PRIMARY, 0));
            throw new AssertionError("unexpected success");
        } catch (HttpDataSource.InvalidResponseCodeException expected) {
            check(expected.responseCode == 410 && upstream.requests.size() == 2 && !target.selected,
                    "failed fallback not cached or retried recursively");
        } finally { source.close(); }
        report.append("Unrelated URI and failed fallback: no loop OK\n");
    }

    private void sharesSuccessfulTargetAcrossSeekRequests() throws Exception {
        FileFallbackDataSource.Target target = new FileFallbackDataSource.Target(PRIMARY, DOWNLOAD);
        Fake first = new Fake(404, 0);
        DataSource firstSource = new FileFallbackDataSource(first, target);
        firstSource.open(request(PRIMARY, 0));
        firstSource.close();
        Fake next = new Fake(404, 0);
        DataSource seekSource = new FileFallbackDataSource(next, target);
        seekSource.open(request(PRIMARY, 2_097_152));
        seekSource.close();
        check(next.requests.size() == 1 && next.requests.get(0).uri.toString().equals(DOWNLOAD)
                && next.requests.get(0).position == 2_097_152, "seek skips missing primary");
        report.append("Seek on another data source: one download request OK\n");
    }

    private void liveMedia() throws Exception {
        String[][] videos = {
                {"tube.azbyka.ru", "74a3053c-40ac-4d03-9c2a-3a813db6428d"},
                {"peertube.terranout.mine.nu", "bde0ec8d-dbbe-464f-b61e-ef32d8939393"},
                {"pablo.tube", "b7f31ce1-5242-4f83-87cf-bc2de3cb24ca"},
                {"pablo.tube", "b61e2ad7-89e3-4434-a862-4a178faccb9c"}
        };
        for (String[] video : videos) {
            String base = "https://" + video[0];
            String page = base + "/videos/watch/" + video[1];
            String api = base + "/api/v1/videos/" + video[1];
            PlaybackInfo audio = PeerTubeClient.resolve(api, true, true);
            check("audio/mp4".equals(audio.streamMimeType), "still separate audio " + page);
            DataSource.Factory factory = PlaybackEngineFactory.http(PeerTubeClient.SOURCE, page,
                    audio.streamUrl, audio.fallbackUrl);
            List<String> attempts = new ArrayList<>();
            boolean available = true;
            int timeoutRetries = 0;
            for (long position : new long[]{0, 1_048_576, 2_097_152}) {
                DataSource source = factory.createDataSource();
                source.addTransferListener(recorder(attempts));
                DataSpec spec = new DataSpec.Builder().setUri(audio.streamUrl).setPosition(position).setLength(4096).build();
                try {
                    readRange(source, spec);
                } catch (IOException error) {
                    // A remote timeout is not evidence of a regression: repeat the same request
                    // through the original data source without the new fallback wrapper.
                    DataSource baseline = PlaybackEngineFactory.http(PeerTubeClient.SOURCE, page,
                            audio.streamUrl, null).createDataSource();
                    IOException previousError = null;
                    try {
                        baseline.open(spec);
                        baseline.read(new byte[4096], 0, 4096);
                    } catch (IOException oldError) {
                        previousError = oldError;
                    } finally { baseline.close(); }
                    if (isTimeout(error) && isTimeout(previousError)) {
                        report.append("LIVE audio TIMEOUT WITH AND WITHOUT WRAPPER: ").append(page).append('\n');
                        available = false;
                        break;
                    }
                    check(isTimeout(error) && previousError == null, "media regression " + page + ": " + error);
                    // A single cold-CDN timeout followed by success is also possible without this
                    // change. Require the identical wrapped request to succeed on one bounded retry.
                    source.close();
                    source = factory.createDataSource();
                    source.addTransferListener(recorder(attempts));
                    timeoutRetries++;
                    readRange(source, spec);
                    report.append("LIVE transient timeout recovered without URL fallback: ").append(page).append('\n');
                } finally { source.close(); }
            }
            boolean brokenPrimary = video[0].equals("tube.azbyka.ru");
            if (available) {
                check(Collections.frequency(attempts, audio.streamUrl) == (brokenPrimary ? 1 : 3) + timeoutRetries,
                        "primary request count " + attempts);
                check(attempts.size() == (brokenPrimary ? 4 : 3) + timeoutRetries, "no preflight or extra retry " + attempts);
                if (brokenPrimary) {
                    check(Collections.frequency(attempts, audio.fallbackUrl) == 3, "download handles all seeks");
                    playbackAfterSeek(audio, page);
                }
                report.append("LIVE audio + two seeks: ").append(page).append(" requests=").append(attempts.size()).append(" OK\n");
            }

            PlaybackInfo normal = PeerTubeClient.resolve(api, false, false);
            boolean hls = "application/x-mpegURL".equals(normal.streamMimeType);
            if (hls) check(normal.fallbackUrl == null, "master has no file-download substitute");
            // Existing resolution may choose a direct muxed file when the HLS probe times out.
            DataSource.Factory normalFactory = PlaybackEngineFactory.http(PeerTubeClient.SOURCE, page,
                    normal.streamUrl, normal.fallbackUrl);
            DataSource source = normalFactory.createDataSource();
            try {
                readNormal(source, normal, hls);
            } catch (IOException error) {
                if (!isTimeout(error)) throw error;
                source.close();
                source = normalFactory.createDataSource();
                readNormal(source, normal, hls);
                report.append("LIVE normal transient timeout recovered: ").append(page).append('\n');
            } finally { source.close(); }
            report.append("LIVE normal ").append(hls ? "HLS" : "direct muxed file")
                    .append(": ").append(video[0]).append(" OK\n");
        }
    }

    private static void readNormal(DataSource source, PlaybackInfo normal, boolean hls) throws IOException {
        if (!hls) {
            readRange(source, new DataSpec.Builder().setUri(normal.streamUrl).setLength(4096).build());
            return;
        }
        source.open(new DataSpec(Uri.parse(normal.streamUrl)));
        byte[] prefix = new byte[7];
        int count = 0;
        while (count < prefix.length) {
            int read = source.read(prefix, count, prefix.length - count);
            if (read < 0) break;
            count += read;
        }
        check(new String(prefix, java.nio.charset.StandardCharsets.US_ASCII).equals("#EXTM3U"), "HLS master readable");
    }

    private static void readRange(DataSource source, DataSpec spec) throws IOException {
        source.open(spec);
        int count = 0;
        byte[] bytes = new byte[4096];
        while (count < bytes.length) {
            int read = source.read(bytes, count, bytes.length - count);
            if (read < 0) break;
            count += read;
        }
        check(count == 4096, "full Range at " + spec.position);
        String range = source.getResponseHeaders().entrySet().stream()
                .filter(e -> "Content-Range".equalsIgnoreCase(e.getKey()))
                .map(e -> e.getValue().get(0)).findFirst().orElse("");
        check(range.startsWith("bytes " + spec.position + "-" + (spec.position + 4095) + "/"), "exact Range " + range);
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException) return true;
        }
        return false;
    }

    private void playbackAfterSeek(PlaybackInfo audio, String page) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Throwable[] failure = new Throwable[1];
        ExoPlayer[] player = new ExoPlayer[1];
        runOnMainSync(() -> {
            try {
                player[0] = PlaybackEngineFactory.create(getTargetContext(), PeerTubeClient.SOURCE, page,
                        false, 0, true, audio.streamUrl, audio.fallbackUrl);
                player[0].addListener(new Player.Listener() {
                    @Override public void onPlayerError(PlaybackException error) {
                        failure[0] = error;
                        done.countDown();
                    }
                    @Override public void onIsPlayingChanged(boolean playing) {
                        if (!playing || done.getCount() == 0) return;
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (done.getCount() == 0) return;
                            try {
                                check(player[0].getCurrentPosition() > 3_600_000, "playback advances after seek");
                                check(player[0].getAudioFormat() != null && player[0].getAudioDecoderCounters() != null
                                        && player[0].getAudioDecoderCounters().renderedOutputBufferCount > 0,
                                        "audio decoded/rendered after fallback");
                            } catch (Throwable error) { failure[0] = error; }
                            done.countDown();
                        }, 1500);
                    }
                });
                player[0].setMediaItem(PlaybackEngineFactory.mediaItem(audio.streamUrl, audio.streamMimeType));
                player[0].seekTo(3_600_000);
                player[0].prepare();
                player[0].play();
            } catch (Throwable error) {
                failure[0] = error;
                done.countDown();
            }
        });
        try {
            check(done.await(30, TimeUnit.SECONDS), "audio playback ready timeout");
            if (failure[0] != null) throw new AssertionError("audio playback failed", failure[0]);
        } finally {
            runOnMainSync(() -> { if (player[0] != null) player[0].release(); });
        }
        report.append("LIVE ExoPlayer: decoded/rendered audio after seek to 01:00:00 OK\n");
    }

    private static TransferListener recorder(List<String> attempts) {
        return new TransferListener() {
            @Override public void onTransferInitializing(DataSource source, DataSpec spec, boolean network) {
                attempts.add(spec.uri.toString());
            }
            @Override public void onTransferStart(DataSource source, DataSpec spec, boolean network) { }
            @Override public void onBytesTransferred(DataSource source, DataSpec spec, boolean network, int bytes) { }
            @Override public void onTransferEnd(DataSource source, DataSpec spec, boolean network) { }
        };
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class Fake implements DataSource {
        final int primaryStatus;
        final int downloadStatus;
        final List<DataSpec> requests = new ArrayList<>();
        int closed;

        Fake(int primaryStatus, int downloadStatus) {
            this.primaryStatus = primaryStatus;
            this.downloadStatus = downloadStatus;
        }
        @Override public long open(DataSpec spec) throws IOException {
            requests.add(spec);
            int status = spec.uri.toString().equals(DOWNLOAD) ? downloadStatus : primaryStatus;
            if (status == -1) throw new IOException("network unavailable");
            if (status != 0) throw new HttpDataSource.InvalidResponseCodeException(status, "test", null,
                    Collections.emptyMap(), spec, new byte[0]);
            return spec.length;
        }
        @Override public int read(byte[] buffer, int offset, int length) { return length; }
        @Override public void close() { closed++; }
        @Override public Uri getUri() { return requests.get(requests.size() - 1).uri; }
        @Override public Map<String, List<String>> getResponseHeaders() { return Collections.emptyMap(); }
        @Override public void addTransferListener(TransferListener listener) { }
    }
}
