package ru.tubetv.app;

final class PlaybackInfo {
    final String streamUrl;
    final int maxWidth;
    final int maxHeight;
    final long loadedAt;

    PlaybackInfo(String streamUrl, int maxWidth, int maxHeight) {
        this.streamUrl = streamUrl;
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.loadedAt = System.currentTimeMillis();
    }
}
