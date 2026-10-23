package ru.tubetv.app;

final class PlaybackInfo {
    final String streamUrl;
    final String streamMimeType;
    final String fallbackUrl;
    final int maxWidth;
    final int maxHeight;

    PlaybackInfo(String streamUrl, int maxWidth, int maxHeight) {
        this(streamUrl, null, maxWidth, maxHeight);
    }

    PlaybackInfo(String streamUrl, String streamMimeType, int maxWidth, int maxHeight) {
        this(streamUrl, streamMimeType, maxWidth, maxHeight, null);
    }

    PlaybackInfo(String streamUrl, String streamMimeType, int maxWidth, int maxHeight, String fallbackUrl) {
        this.streamUrl = streamUrl;
        this.streamMimeType = streamMimeType;
        this.fallbackUrl = fallbackUrl;
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
    }

    MediaCapabilities capabilities() { return new MediaCapabilities(maxWidth, maxHeight); }
}
