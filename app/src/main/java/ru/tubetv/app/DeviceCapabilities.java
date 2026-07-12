package ru.tubetv.app;

import android.media.MediaCodecList;
import android.os.Build;

import androidx.annotation.OptIn;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@OptIn(markerClass = UnstableApi.class)
final class DeviceCapabilities {
    private static volatile DeviceCapabilities instance;
    private final Set<String> hardwareVideo;
    private final Set<String> hardwareAudio;

    static DeviceCapabilities get() {
        DeviceCapabilities result = instance;
        if (result != null) return result;
        synchronized (DeviceCapabilities.class) {
            if (instance == null) instance = detect();
            return instance;
        }
    }

    static void warmUp() { get(); }

    boolean supportsHardware(String mimeType) {
        if (mimeType == null) return false;
        return mimeType.startsWith("video/") ? hardwareVideo.contains(mimeType)
                : !mimeType.startsWith("audio/") || hardwareAudio.contains(mimeType);
    }

    String[] preferredVideoMimes(boolean trafficMode) {
        String[] order = trafficMode
                ? new String[]{MimeTypes.VIDEO_AV1, MimeTypes.VIDEO_H265,
                    MimeTypes.VIDEO_VP9, MimeTypes.VIDEO_H264}
                : new String[]{MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265,
                    MimeTypes.VIDEO_VP9, MimeTypes.VIDEO_AV1};
        return supported(order, hardwareVideo);
    }

    String[] preferredAudioMimes(boolean trafficMode) {
        String[] order = trafficMode
                ? new String[]{MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_MPEG}
                : new String[]{MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_MPEG};
        return supported(order, hardwareAudio);
    }

    Set<String> hardwareVideoMimes() { return hardwareVideo; }
    Set<String> hardwareAudioMimes() { return hardwareAudio; }

    private DeviceCapabilities(Set<String> video, Set<String> audio) {
        hardwareVideo = Collections.unmodifiableSet(video);
        hardwareAudio = Collections.unmodifiableSet(audio);
    }

    private static DeviceCapabilities detect() {
        Set<String> video = new HashSet<>();
        Set<String> audio = new HashSet<>();
        try {
            android.media.MediaCodecInfo[] codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS)
                    .getCodecInfos();
            for (android.media.MediaCodecInfo codec : codecs) {
                if (codec.isEncoder() || !isHardware(codec)) continue;
                for (String type : codec.getSupportedTypes()) {
                    if (type == null) continue;
                    String mime = type.toLowerCase(Locale.ROOT);
                    if (mime.startsWith("video/")) video.add(mime);
                    else if (mime.startsWith("audio/")) audio.add(mime);
                }
            }
        } catch (Throwable ignored) { }
        return new DeviceCapabilities(video, audio);
    }

    private static boolean isHardware(android.media.MediaCodecInfo codec) {
        if (Build.VERSION.SDK_INT >= 29) return codec.isHardwareAccelerated() && !codec.isSoftwareOnly();
        String name = codec.getName().toLowerCase(Locale.ROOT);
        return !name.startsWith("omx.google.") && !name.startsWith("c2.android.")
                && !name.startsWith("c2.google.") && !name.contains("software")
                && !name.contains("ffmpeg") && !name.contains(".sw.");
    }

    private static String[] supported(String[] order, Set<String> available) {
        List<String> result = new ArrayList<>();
        for (String mime : order) if (available.contains(mime)) result.add(mime);
        return result.toArray(new String[0]);
    }

}
