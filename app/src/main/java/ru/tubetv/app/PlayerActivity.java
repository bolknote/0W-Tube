package ru.tubetv.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.util.ArrayList;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlayerActivity extends Activity {
    private final ExecutorService resolver = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable progressTicker = new Runnable() {
        @Override public void run() {
            updateControls();
            ui.postDelayed(this, 1000);
        }
    };
    private ExoPlayer player;
    private SurfaceView videoSurface;
    private ProgressBar loading;
    private ProgressBar timeline;
    private TextView message;
    private TextView playState;
    private TextView time;
    private LinearLayout controls;
    private final Runnable hideControls = () -> controls.setVisibility(View.GONE);
    private String streamUrl;
    private boolean autoPlay;
    private long resumePosition;
    private long knownDuration;
    private long lastSavedAt;
    private Thread.UncaughtExceptionHandler previousCrashHandler;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            CrashLog.save(getApplicationContext(), error);
            if (previousCrashHandler != null) previousCrashHandler.uncaughtException(thread, error);
        });
        autoPlay = getIntent().getBooleanExtra("auto_play", true);
        resumePosition = Math.max(0L, getIntent().getLongExtra("resume_position", 0L));
        knownDuration = Math.max(0L, getIntent().getLongExtra("duration_ms", 0L));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        setContentView(createContent());
        resolveStream();
    }

    private View createContent() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        videoSurface = new SurfaceView(this);
        root.addView(videoSurface, new FrameLayout.LayoutParams(-1, -1));

        loading = new ProgressBar(this);
        root.addView(loading, new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER));
        message = new TextView(this);
        message.setTextColor(Color.WHITE);
        message.setTextSize(18);
        message.setGravity(Gravity.CENTER);
        message.setPadding(dp(30), dp(20), dp(30), dp(20));
        root.addView(message, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        message.setText("Получаю видеопоток…");

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setBackgroundResource(R.drawable.player_controls_background);
        controls.setVisibility(View.GONE);

        TextView title = label(getIntent().getStringExtra("title"), 17);
        title.setSingleLine(true);
        controls.addView(title, new LinearLayout.LayoutParams(-1, dp(28)));

        timeline = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        timeline.setMax(1000);
        controls.addView(timeline, new LinearLayout.LayoutParams(-1, dp(12)));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView rewind = controlButton("◀  −15 сек");
        playState = controlButton("▶");
        TextView forward = controlButton("+30 сек  ▶");
        time = label("0:00 / 0:00", 15);
        time.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.addView(rewind, new LinearLayout.LayoutParams(dp(150), dp(42)));
        row.addView(playState, new LinearLayout.LayoutParams(dp(90), dp(42)));
        row.addView(forward, new LinearLayout.LayoutParams(dp(150), dp(42)));
        row.addView(time, new LinearLayout.LayoutParams(0, dp(42), 1f));
        controls.addView(row, new LinearLayout.LayoutParams(-1, dp(46)));

        FrameLayout.LayoutParams panel = new FrameLayout.LayoutParams(-1, dp(112), Gravity.BOTTOM);
        panel.setMargins(dp(28), 0, dp(28), dp(24));
        root.addView(controls, panel);
        return root;
    }

    private TextView label(String value, int sp) {
        TextView label = new TextView(this);
        label.setText(value == null ? "" : value);
        label.setTextColor(Color.WHITE);
        label.setTextSize(sp);
        return label;
    }

    private TextView controlButton(String value) {
        TextView button = label(value, 15);
        button.setGravity(Gravity.CENTER);
        button.setBackgroundResource(R.drawable.filter_chip_background);
        return button;
    }

    private void resolveStream() {
        String url = getIntent().getStringExtra("resolver_url");
        resolver.execute(() -> {
            try {
                String resolved = new StreamResolver().resolve(url);
                runOnUiThread(() -> { streamUrl = resolved; startPlayerSafely(); });
            } catch (Exception error) {
                runOnUiThread(() -> showError(error.getMessage() == null ? "Видео больше недоступно" : error.getMessage()));
            }
        });
    }

    private void startPlayerSafely() {
        try {
            startPlayer();
        } catch (Throwable error) {
            CrashLog.save(getApplicationContext(), error);
            String detail = error.getClass().getSimpleName();
            if (error.getMessage() != null) detail += ": " + error.getMessage();
            showError("Сбой инициализации плеера: " + detail);
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    private void startPlayer() {
        if (streamUrl == null || player != null) return;
        Map<String, String> headers = new HashMap<>();
        String source = getIntent().getStringExtra("source");
        boolean vk = "VK VIDEO".equals(source);
        boolean dzen = "ДЗЕН".equals(source);
        if (dzen) {
            CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
        } else {
            headers.put("Referer", vk ? "https://vkvideo.ru/" : "https://rutube.ru/");
        }
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(dzen ? DzenClient.USER_AGENT : vk
                        ? "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/150 Safari/537.36"
                        : "Mozilla/5.0 (Linux; Android 11; Android TV) AppleWebKit/537.36")
                .setConnectTimeoutMs(8_000)
                .setReadTimeoutMs(10_000)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(headers);
        MediaCodecSelector hardwareVideoSelector = (mimeType, secure, tunneling) -> {
            List<MediaCodecInfo> available = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling);
            if (!mimeType.startsWith("video/")) return available;
            List<MediaCodecInfo> hardware = new ArrayList<>();
            for (MediaCodecInfo codec : available) {
                if (codec.hardwareAccelerated && !codec.softwareOnly) hardware.add(codec);
            }
            return hardware;
        };
        player = new ExoPlayer.Builder(this)
                .setRenderersFactory(new DefaultRenderersFactory(this)
                        .setMediaCodecSelector(hardwareVideoSelector)
                        .setEnableDecoderFallback(true))
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                .build();
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setMaxVideoSize(3840, 2160)
                .setForceHighestSupportedBitrate(true)
                .build());
        player.setVideoSurfaceView(videoSurface);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    loading.setVisibility(View.GONE);
                    message.setVisibility(View.GONE);
                    if (!autoPlay) showControls();
                } else if (state == Player.STATE_ENDED) {
                    showControls();
                }
            }

            @Override public void onIsPlayingChanged(boolean isPlaying) {
                updateControls();
                ui.removeCallbacks(hideControls);
                if (isPlaying && controls.getVisibility() == View.VISIBLE) {
                    ui.postDelayed(hideControls, 3500);
                }
            }

            @Override public void onVideoSizeChanged(VideoSize size) {
                fitSurface(size.width, size.height);
            }

            @Override public void onPlayerError(PlaybackException error) {
                showError("Ошибка воспроизведения: " + error.getErrorCodeName());
            }
        });
        MediaItem.Builder item = new MediaItem.Builder().setUri(streamUrl);
        if (streamUrl.contains(".m3u8")) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaItem(item.build());
        if (resumePosition > 0) player.seekTo(resumePosition);
        player.setPlayWhenReady(autoPlay);
        player.prepare();
        ui.post(progressTicker);
    }

    private void showControls() {
        controls.setVisibility(View.VISIBLE);
        updateControls();
        ui.removeCallbacks(hideControls);
        if (player != null && player.isPlaying()) ui.postDelayed(hideControls, 3500);
    }

    private void updateControls() {
        if (player == null) return;
        long position = Math.max(0, player.getCurrentPosition());
        long duration = player.getDuration();
        if (duration <= 0 || duration == androidx.media3.common.C.TIME_UNSET) {
            timeline.setProgress(0);
            time.setText(formatTime(position) + " / —");
        } else {
            timeline.setProgress((int) Math.min(1000, position * 1000 / duration));
            time.setText(formatTime(position) + " / " + formatTime(duration));
        }
        playState.setText(player.isPlaying() ? "Ⅱ" : "▶");
        long now = System.currentTimeMillis();
        if (now - lastSavedAt > 5000) {
            StateStore.savePlayerPosition(this, position);
            saveWatchProgress(position, duration);
            lastSavedAt = now;
        }
    }

    private static String formatTime(long millis) {
        long total = Math.max(0, millis / 1000);
        long hours = total / 3600;
        long minutes = total % 3600 / 60;
        long seconds = total % 60;
        return hours > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    private void fitSurface(int width, int height) {
        if (width <= 0 || height <= 0) return;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        float ratio = Math.min(screenW / (float) width, screenH / (float) height);
        videoSurface.setLayoutParams(new FrameLayout.LayoutParams(
                Math.round(width * ratio), Math.round(height * ratio), Gravity.CENTER));
    }

    private void showError(String text) {
        loading.setVisibility(View.GONE);
        controls.setVisibility(View.GONE);
        message.setVisibility(View.VISIBLE);
        message.setText(text + "\n\nOK — открыть официальный плеер");
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (player != null && player.getPlaybackState() != Player.STATE_IDLE) {
                if (player.isPlaying()) player.pause(); else player.play();
                showControls();
            } else openOfficial();
            return true;
        }
        if (player != null && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            player.seekTo(Math.max(0, player.getCurrentPosition() - 15_000));
            showControls();
            return true;
        }
        if (player != null && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            long target = player.getCurrentPosition() + 30_000;
            if (player.getDuration() > 0) target = Math.min(target, player.getDuration());
            player.seekTo(target);
            showControls();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            showControls();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public void onBackPressed() {
        saveProgress();
        StateStore.markSearch(this);
        super.onBackPressed();
    }

    private void saveProgress() {
        if (player != null) {
            long position = player.getCurrentPosition();
            StateStore.savePlayerPosition(this, position);
            saveWatchProgress(position, player.getDuration());
        }
    }

    private void saveWatchProgress(long position, long duration) {
        long actualDuration = duration > 0 && duration != androidx.media3.common.C.TIME_UNSET
                ? duration : knownDuration;
        if (actualDuration > 0) knownDuration = actualDuration;
        WatchProgressStore.save(this,
                getIntent().getStringExtra("source"),
                getIntent().getStringExtra("page_url"),
                Math.max(0L, position),
                Math.max(0L, actualDuration));
    }

    private void openOfficial() {
        String page = getIntent().getStringExtra("page_url");
        if (page == null) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(page))); } catch (Exception ignored) { }
    }

    @Override protected void onStart() {
        super.onStart();
        if (player != null && !player.isPlaying()) showControls();
    }

    @Override protected void onStop() {
        saveProgress();
        if (player != null) player.pause();
        super.onStop();
    }

    @Override protected void onDestroy() {
        resolver.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        if (player != null) {
            player.setVideoSurfaceView(null);
            player.release();
            player = null;
        }
        if (Thread.getDefaultUncaughtExceptionHandler() != previousCrashHandler) {
            Thread.setDefaultUncaughtExceptionHandler(previousCrashHandler);
        }
        super.onDestroy();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
