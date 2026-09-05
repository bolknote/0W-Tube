package ru.tubetv.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

public final class AudioPlaybackService extends Service {
    static final String ACTION_PLAY = "ru.tubetv.app.PLAY_BACKGROUND_AUDIO";
    private static final String ACTION_STOP = "ru.tubetv.app.STOP_BACKGROUND_AUDIO";
    private static final String CHANNEL_ID = "background_audio";
    private static final int NOTIFICATION_ID = 5801;

    // Activity and Service callbacks run on the main thread. Invalidate queued starts
    // when the screen takes playback back, before Android delivers stopService().
    private static long requestGeneration;
    private static AudioPlaybackService active;

    static long prepareStart(android.content.Context context) {
        stopPlayback(context);
        return requestGeneration;
    }

    static void stopPlayback(android.content.Context context) {
        requestGeneration++;
        if (active != null) active.releasePlayer();
        context.stopService(new Intent(context, AudioPlaybackService.class));
    }

    static long takePosition(android.content.Context context, VideoItem item, long fallback) {
        long position = fallback;
        if (active != null && active.player != null
                && java.util.Objects.equals(active.source, item.source)
                && java.util.Objects.equals(active.pageUrl, item.pageUrl)) {
            position = active.player.getCurrentPosition();
        } else {
            VideoItem saved = StateStore.playerItem(context);
            if (saved != null && saved.stableKey().equals(item.stableKey())) position = StateStore.position(context);
        }
        stopPlayback(context);
        return Math.max(0L, position);
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable progressTicker = new Runnable() {
        @Override public void run() {
            saveProgress();
            if (player != null) ui.postDelayed(this, 2000);
        }
    };
    private ExoPlayer player;
    private String source;
    private String pageUrl;
    private long knownDuration;
    private VideoItem historyItem;
    private boolean historyRecordedThisSession;
    private boolean trafficMode;
    private int targetHeight;

    @Override public void onCreate() {
        super.onCreate();
        active = this;
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_PLAY.equals(intent.getAction()) || DeviceType.isTelevision(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent.getLongExtra("background_request", -1L) != requestGeneration) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        String streamUrl = intent.getStringExtra("stream_url");
        if (streamUrl == null || streamUrl.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        releasePlayer();
        source = intent.getStringExtra("source");
        pageUrl = intent.getStringExtra("page_url");
        knownDuration = Math.max(0L, intent.getLongExtra("duration_ms", 0L));
        trafficMode = intent.getBooleanExtra("traffic_mode", false);
        targetHeight = Math.max(0, intent.getIntExtra("target_height", 0));
        historyItem = new VideoItem(
                source,
                intent.getStringExtra("title"),
                intent.getStringExtra("subtitle"),
                intent.getStringExtra("thumbnail"),
                intent.getStringExtra("resolver_url"),
                pageUrl,
                knownDuration)
                .withQuality(Math.max(0, intent.getIntExtra("max_width", 0)),
                        Math.max(0, intent.getIntExtra("max_height", 0)));
        historyRecordedThisSession = WatchHistoryStore.contains(this, historyItem);
        startForeground(NOTIFICATION_ID, buildNotification(intent));
        try {
            startPlayer(streamUrl, intent.getStringExtra("stream_mime_type"),
                    Math.max(0L, intent.getLongExtra("resume_position", 0L)),
                    intent.getFloatExtra("playback_speed", 1f));
        } catch (RuntimeException error) {
            CrashLog.save(this, error);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @OptIn(markerClass = UnstableApi.class)
    private void startPlayer(String streamUrl, String streamMimeType, long position, float speed) {
        player = PlaybackEngineFactory.create(this, source, pageUrl, trafficMode, targetHeight, true);
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) { stopSelf(); }
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) stopSelf();
            }
        });
        player.setMediaItem(PlaybackEngineFactory.mediaItem(streamUrl, streamMimeType));
        if (position > 0) player.seekTo(position);
        player.setPlaybackSpeed(speed);
        player.prepare();
        player.play();
        ui.post(progressTicker);
    }

    private android.app.Notification buildNotification(Intent sourceIntent) {
        Intent open = new Intent(this, PlayerActivity.class);
        if (sourceIntent.getExtras() != null) open.putExtras(sourceIntent.getExtras());
        open.removeExtra("stream_url");
        open.removeExtra("stream_mime_type");
        open.putExtra("resume_background_audio", true);
        open.putExtra("auto_play", true);
        open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 5801, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, AudioPlaybackService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 5802, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = sourceIntent.getStringExtra("title");
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_speaker)
                .setContentTitle(title == null || title.isEmpty() ? "0W-Tube" : title)
                .setContentText("Воспроизводится только звук")
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setColor(Color.rgb(134, 111, 255))
                .addAction(0, "Остановить", stop)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Фоновое воспроизведение", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Звук из видео 0W-Tube");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private void saveProgress() {
        if (player == null) return;
        long position = Math.max(0L, player.getCurrentPosition());
        long duration = player.getDuration();
        if (duration <= 0 || duration == C.TIME_UNSET) duration = knownDuration;
        if (duration > 0) knownDuration = duration;
        StateStore.savePlayerPosition(this, source, pageUrl, position);
        WatchProgressStore.save(this, source, pageUrl, position, Math.max(0L, duration));
        if (!historyRecordedThisSession && position >= WatchProgressStore.MIN_POSITION_MS) {
            WatchHistoryStore.record(this, historyItem.withDuration(duration),
                    trafficMode, targetHeight, true);
            historyRecordedThisSession = true;
        }
    }

    private void releasePlayer() {
        ui.removeCallbacks(progressTicker);
        if (player == null) return;
        saveProgress();
        player.release();
        player = null;
    }

    @Override public void onDestroy() {
        releasePlayer();
        if (active == this) active = null;
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
