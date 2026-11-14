package ru.tubetv.app;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** In-process URL fixtures: the Activity, resolver, Media3 and Android lifecycle are real. */
final class PlaybackRecoveryScenario {
    private static final String VK_ID = "-42_123";
    private static final String PAGE = "https://vkvideo.ru/video" + VK_ID;
    private static final int BYTE_RATE = 32_000; // Mono PCM16, 16 kHz.
    private static final int DATA_BYTES = 24 * BYTE_RATE;
    private static final byte[] WAV_HEADER = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put(ascii("RIFF")).putInt(DATA_BYTES + 36).put(ascii("WAVEfmt ")).putInt(16)
            .putShort((short) 1).putShort((short) 1).putInt(16_000).putInt(BYTE_RATE)
            .putShort((short) 2).putShort((short) 16).put(ascii("data")).putInt(DATA_BYTES).array();
    private static final int FILE_BYTES = WAV_HEADER.length + DATA_BYTES;
    private static final Field PLAYER = field("player");
    private static final Field MESSAGE = field("message");

    private static volatile Fixture fixture;

    static void run(Instrumentation test, StringBuilder report) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> !"https".equals(protocol) ? null
                : new URLStreamHandler() {
                    @Override protected URLConnection openConnection(URL url) {
                        return new Connection(url, fixture);
                    }
                });
        recoversWithoutInput(test, report);
        stopsAfterOneAttempt(test, report);
    }

    private static void recoversWithoutInput(Instrumentation test, StringBuilder report) throws Exception {
        Fixture current = new Fixture(false);
        PlayerActivity activity = open(test, current);
        try {
            Snapshot before = waitFor(test, activity, state -> state.playing && state.position >= 1500);
            current.expired.countDown();
            Snapshot after = waitFor(test, activity, state -> state.player != before.player && state.playing);
            check(current.resolutions.get() == 2 && current.freshRequests.get() > 0, "one new resolved stream");
            check(after.position >= before.position, "resume position: " + before.position + " → " + after.position);
            check(after.message == null, "recovery message dismissed");
            report.append("Recovery: expired VK stream replaced automatically at ")
                    .append(after.position).append(" ms, no input\n");
        } finally {
            current.expired.countDown();
            test.runOnMainSync(activity::finish);
            test.waitForIdleSync();
        }
    }

    private static void stopsAfterOneAttempt(Instrumentation test, StringBuilder report) throws Exception {
        Fixture current = new Fixture(true);
        PlayerActivity activity = open(test, current);
        IntentFilter external = new IntentFilter(Intent.ACTION_VIEW);
        external.addDataScheme("https");
        Instrumentation.ActivityMonitor monitor = test.addMonitor(external, null, true);
        try {
            Snapshot failed = waitFor(test, activity, state -> state.player == null && state.message != null
                    && state.message.contains("HTTP 410"));
            check(!failed.message.contains("OK") && !failed.message.contains("официальн"), "no action prompt");
            test.runOnMainSync(() -> {
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
                activity.onKeyDown(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
            });
            check(current.resolutions.get() == 2 && current.freshRequests.get() > 0
                    && monitor.getHits() == 0, "no retry loop or external app");
            report.append("Recovery: terminal error after one retry; no external player\n");
        } finally {
            test.removeMonitor(monitor);
            test.runOnMainSync(activity::finish);
            test.waitForIdleSync();
        }
    }

    private static PlayerActivity open(Instrumentation test, Fixture current) {
        fixture = current;
        Intent intent = new Intent(test.getTargetContext(), PlayerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("resolver_url", PAGE).putExtra("page_url", PAGE)
                .putExtra("source", "VK VIDEO").putExtra("title", "Тест восстановления");
        return (PlayerActivity) test.startActivitySync(intent);
    }

    private interface Condition { boolean matches(Snapshot snapshot); }

    private static Snapshot waitFor(Instrumentation test, PlayerActivity activity, Condition condition) {
        long deadline = SystemClock.elapsedRealtime() + 35_000;
        Snapshot[] last = new Snapshot[1];
        do {
            test.runOnMainSync(() -> last[0] = new Snapshot(activity));
            if (condition.matches(last[0])) return last[0];
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Playback state timeout: " + last[0].position + " ms, " + last[0].message
                + ", resolutions=" + fixture.resolutions.get());
    }

    private static final class Snapshot {
        final ExoPlayer player;
        final boolean playing;
        final long position;
        final String message;

        Snapshot(PlayerActivity activity) {
            try {
                player = (ExoPlayer) PLAYER.get(activity);
                playing = player != null && player.isPlaying() && player.getPlaybackState() == Player.STATE_READY;
                position = player == null ? 0 : player.getCurrentPosition();
                TextView error = (TextView) MESSAGE.get(activity);
                message = error.getVisibility() == View.VISIBLE ? error.getText().toString() : null;
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        }
    }

    private static Field field(String name) {
        try {
            Field field = PlayerActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static final class Fixture {
        final boolean fail;
        final CountDownLatch expired = new CountDownLatch(1);
        final AtomicInteger resolutions = new AtomicInteger();
        final AtomicInteger freshRequests = new AtomicInteger();

        Fixture(boolean fail) { this.fail = fail; }
    }

    private static final class Connection extends HttpURLConnection {
        private final Fixture response;
        private final boolean media, old;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

        Connection(URL url, Fixture fixture) {
            super(url);
            response = fixture;
            media = "media.example".equals(url.getHost());
            old = "/old.wav".equals(url.getPath());
        }

        @Override public void connect() { }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public OutputStream getOutputStream() { return body; }

        private int start() {
            String range = getRequestProperty("Range");
            return range == null ? 0 : Integer.parseInt(range.substring(6, range.indexOf('-')));
        }

        @Override public int getResponseCode() {
            if (!media) return 200;
            if (!old) response.freshRequests.incrementAndGet();
            if (response.fail || (old && response.expired.getCount() == 0)) return 410;
            return start() == 0 ? 200 : 206;
        }

        @Override public String getResponseMessage() { return "fixture"; }

        @Override public String getHeaderField(String name) {
            if (!media) return null;
            if ("Content-Length".equalsIgnoreCase(name)) return Integer.toString(FILE_BYTES - start());
            if ("Content-Range".equalsIgnoreCase(name) && start() > 0) {
                return "bytes " + start() + "-" + (FILE_BYTES - 1) + "/" + FILE_BYTES;
            }
            return null;
        }

        @Override public InputStream getInputStream() throws IOException {
            if (!media) return new ByteArrayInputStream(ascii(metadata()));
            if (getResponseCode() >= 400) throw new IOException("expired fixture");
            final int offset = start();
            return new InputStream() {
                private int position = offset;
                private final int fence = old ? offset + 5 * BYTE_RATE : FILE_BYTES;

                @Override public int read() throws IOException {
                    byte[] value = new byte[1];
                    return read(value, 0, 1) < 0 ? -1 : value[0] & 255;
                }

                @Override public int read(byte[] bytes, int start, int length) throws IOException {
                    if (length == 0) return 0;
                    if (position >= FILE_BYTES) return -1;
                    if (old && position >= fence) {
                        try { response.expired.await(22, TimeUnit.SECONDS); }
                        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                        throw new SocketTimeoutException("expired while playing");
                    }
                    int count = Math.min(length, Math.min(fence, FILE_BYTES) - position);
                    Arrays.fill(bytes, start, start + count, (byte) 0);
                    if (position < WAV_HEADER.length) {
                        System.arraycopy(WAV_HEADER, position, bytes, start,
                                Math.min(count, WAV_HEADER.length - position));
                    }
                    position += count;
                    return count;
                }
            };
        }

        private String metadata() {
            switch (url.getHost()) {
                case "login.vk.com":
                    return "{\"type\":\"okay\",\"data\":{\"access_token\":\"fixture\",\"expired_at\":4102444800}}";
                case "api.vk.com":
                    check("/method/video.getByIds".equals(url.getPath())
                            && new String(body.toByteArray(), StandardCharsets.UTF_8).contains("&videos=" + VK_ID + "&"),
                            "same VK video resolved again");
                    String path = response.resolutions.incrementAndGet() == 1 ? "old.wav" : "fresh.wav";
                    return "{\"response\":{\"items\":[{\"files\":{\"mp4_720\":\"https://media.example/" + path + "\"}}]}}";
                default:
                    throw new AssertionError("Unexpected fixture host: " + url.getHost());
            }
        }
    }

    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private PlaybackRecoveryScenario() { }
}
