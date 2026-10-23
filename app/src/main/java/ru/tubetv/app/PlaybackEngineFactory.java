package ru.tubetv.app;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@OptIn(markerClass = UnstableApi.class)
final class PlaybackEngineFactory {
    static ExoPlayer create(Context context, String source, String pageUrl,
                            boolean trafficMode, int targetHeight, boolean audioOnly,
                            String streamUrl, String fallbackUrl) {
        DeviceCapabilities capabilities = DeviceCapabilities.get();
        MediaCodecSelector selector = (mimeType, secure, tunneling) -> {
            List<MediaCodecInfo> available = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling);
            if (!mimeType.startsWith("video/") && !mimeType.startsWith("audio/")) return available;
            List<MediaCodecInfo> hardware = new ArrayList<>();
            for (MediaCodecInfo codec : available) {
                if (codec.hardwareAccelerated && !codec.softwareOnly) hardware.add(codec);
            }
            if (mimeType.startsWith("video/")) {
                return capabilities.supportsHardware(mimeType) ? hardware : new ArrayList<>();
            }
            // Some TV hardware AAC decoders accept mono only. Audio may fall back;
            // software video is never included in the selector's result.
            List<MediaCodecInfo> ordered = new ArrayList<>(hardware);
            for (MediaCodecInfo codec : available) if (!hardware.contains(codec)) ordered.add(codec);
            return ordered;
        };
        ExoPlayer player = new ExoPlayer.Builder(context)
                .setRenderersFactory(new DefaultRenderersFactory(context)
                        .setMediaCodecSelector(selector).setEnableDecoderFallback(true))
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http(source, pageUrl, streamUrl, fallbackUrl)))
                .build();
        TrackSelectionParameters.Builder tracks = player.getTrackSelectionParameters().buildUpon();
        String[] video = capabilities.preferredVideoMimes(trafficMode);
        String[] audio = capabilities.preferredAudioMimes(trafficMode || audioOnly);
        if (video.length > 0) tracks.setPreferredVideoMimeTypes(video);
        if (audio.length > 0) tracks.setPreferredAudioMimeTypes(audio);
        if (audioOnly) {
            tracks.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).setForceLowestBitrate(true);
        } else if (trafficMode) {
            int height = targetHeight > 0 ? targetHeight : 144;
            int width = height == 240 ? 426 : (height * 16 + 8) / 9;
            tracks.setMaxVideoSize(width, height).setForceHighestSupportedBitrate(true);
        } else {
            tracks.setMaxVideoSize(3840, 2160).setForceHighestSupportedBitrate(true);
        }
        player.setTrackSelectionParameters(tracks.build());
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(audioOnly ? C.AUDIO_CONTENT_TYPE_MUSIC : C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), true);
        return player;
    }

    static MediaItem mediaItem(String url, String mimeType) {
        MediaItem.Builder item = new MediaItem.Builder().setUri(url);
        if (mimeType != null) item.setMimeType(mimeType);
        else if (url.contains(".m3u8")) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        else if (url.contains(".mpd")) item.setMimeType(MimeTypes.APPLICATION_MPD);
        return item.build();
    }

    static DataSource.Factory http(String source, String pageUrl, String streamUrl, String fallbackUrl) {
        boolean vk = "VK VIDEO".equals(source);
        boolean dzen = "ДЗЕН".equals(source);
        boolean ok = "OK".equals(source);
        boolean peerTube = PeerTubeClient.SOURCE.equals(source);
        Map<String, String> headers = peerTube ? PeerTubeClient.mediaHeaders(pageUrl) : new HashMap<>();
        if (!dzen && !peerTube) {
            String referer = vk ? "https://vkvideo.ru/" : ok ? OkClient.REFERER : "https://rutube.ru/";
            if (referer != null) headers.put("Referer", referer);
        }
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(dzen ? DzenClient.USER_AGENT : vk ? VkWebClient.USER_AGENT
                        : ok ? OkClient.USER_AGENT
                        : peerTube ? PeerTubeClient.MEDIA_USER_AGENT
                        : "Mozilla/5.0 (Linux; Android 11; Android TV) AppleWebKit/537.36")
                .setConnectTimeoutMs(8_000).setReadTimeoutMs(10_000)
                .setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(headers);
        CookieHandler cookies = dzen ? new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
                : ScopedCookies.NONE;
        FileFallbackDataSource.Target target = peerTube && streamUrl != null && fallbackUrl != null
                && !streamUrl.equals(fallbackUrl) ? new FileFallbackDataSource.Target(streamUrl, fallbackUrl) : null;
        return () -> {
            DataSource upstream = new CookieDataSource(http.createDataSource(), cookies);
            return target == null ? upstream : new FileFallbackDataSource(upstream, target);
        };
    }

    private static final class CookieDataSource implements DataSource {
        private final DataSource upstream;
        private final CookieHandler cookies;
        CookieDataSource(DataSource upstream, CookieHandler cookies) {
            this.upstream = upstream;
            this.cookies = cookies;
        }
        @Override public long open(DataSpec spec) throws IOException {
            return ScopedCookies.with(cookies, () -> upstream.open(spec));
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            return upstream.read(buffer, offset, length);
        }
        @Override public void close() throws IOException { upstream.close(); }
        @Override public Uri getUri() { return upstream.getUri(); }
        @Override public Map<String, List<String>> getResponseHeaders() { return upstream.getResponseHeaders(); }
        @Override public void addTransferListener(TransferListener listener) { upstream.addTransferListener(listener); }
    }

    private PlaybackEngineFactory() { }
}
