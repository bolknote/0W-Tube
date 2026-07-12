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
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AudioPlaybackService extends Service {
    static final String ACTION_PLAY = "ru.tubetv.app.PLAY_BACKGROUND_AUDIO";
    private static final String ACTION_STOP = "ru.tubetv.app.STOP_BACKGROUND_AUDIO";
    private static final String CHANNEL_ID = "background_audio";
    private static final int NOTIFICATION_ID = 5801;

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

    @Override public void onCreate() {
        super.onCreate();
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
        String streamUrl = intent.getStringExtra("stream_url");
        if (streamUrl == null || streamUrl.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        source = intent.getStringExtra("source");
        pageUrl = intent.getStringExtra("page_url");
        knownDuration = Math.max(0L, intent.getLongExtra("duration_ms", 0L));
        startForeground(NOTIFICATION_ID, buildNotification(intent));
        startPlayer(streamUrl, Math.max(0L, intent.getLongExtra("resume_position", 0L)));
        return START_NOT_STICKY;
    }

    @OptIn(markerClass = UnstableApi.class)
    private void startPlayer(String streamUrl, long position) {
        releasePlayer();
        Map<String, String> headers = new HashMap<>();
        boolean vk = "VK VIDEO".equals(source);
        boolean dzen = "ДЗЕН".equals(source);
        if (!dzen) headers.put("Referer", vk ? "https://vkvideo.ru/" : "https://rutube.ru/");
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(dzen ? DzenClient.USER_AGENT : vk
                        ? "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/150 Safari/537.36"
                        : "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36")
                .setConnectTimeoutMs(8_000)
                .setReadTimeoutMs(10_000)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(headers);
        DeviceCapabilities capabilities = DeviceCapabilities.get();
        MediaCodecSelector hardwareSelector = (mimeType, secure, tunneling) -> {
            List<MediaCodecInfo> available = MediaCodecSelector.DEFAULT
                    .getDecoderInfos(mimeType, secure, tunneling);
            if (!mimeType.startsWith("audio/") && !mimeType.startsWith("video/")) return available;
            if (!capabilities.supportsHardware(mimeType)) return new ArrayList<>();
            List<MediaCodecInfo> hardware = new ArrayList<>();
            for (MediaCodecInfo codec : available) {
                if (codec.hardwareAccelerated && !codec.softwareOnly) hardware.add(codec);
            }
            return hardware;
        };
        player = new ExoPlayer.Builder(this)
                .setRenderersFactory(new DefaultRenderersFactory(this)
                        .setMediaCodecSelector(hardwareSelector)
                        .setEnableDecoderFallback(true))
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                .build();
        String[] preferredAudio = capabilities.preferredAudioMimes(true);
        androidx.media3.common.TrackSelectionParameters.Builder tracks =
                player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                        .setForceLowestBitrate(true);
        if (preferredAudio.length > 0) tracks.setPreferredAudioMimeTypes(preferredAudio);
        player.setTrackSelectionParameters(tracks.build());
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(), true);
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) { stopSelf(); }
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) stopSelf();
            }
        });
        MediaItem.Builder item = new MediaItem.Builder().setUri(streamUrl);
        if (streamUrl.contains(".m3u8")) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaItem(item.build());
        if (position > 0) player.seekTo(position);
        player.prepare();
        player.play();
        ui.post(progressTicker);
    }

    private android.app.Notification buildNotification(Intent sourceIntent) {
        Intent open = new Intent(this, PlayerActivity.class);
        if (sourceIntent.getExtras() != null) open.putExtras(sourceIntent.getExtras());
        open.removeExtra("stream_url");
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
        StateStore.savePlayerPosition(this, position);
        WatchProgressStore.save(this, source, pageUrl, position, Math.max(0L, duration));
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
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
