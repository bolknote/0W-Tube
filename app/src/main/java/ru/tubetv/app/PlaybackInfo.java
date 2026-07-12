package ru.tubetv.app;

final class PlaybackInfo {
    final String streamUrl;
    final String streamMimeType;
    final int maxWidth;
    final int maxHeight;
    final long loadedAt;

    PlaybackInfo(String streamUrl, int maxWidth, int maxHeight) {
        this(streamUrl, null, maxWidth, maxHeight);
    }

    PlaybackInfo(String streamUrl, String streamMimeType, int maxWidth, int maxHeight) {
        this.streamUrl = streamUrl;
        this.streamMimeType = streamMimeType;
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.loadedAt = System.currentTimeMillis();
    }
}
