package ru.tubetv.app;

/** Search metadata may be cached without keeping a temporary playback URL alive. */
final class MediaCapabilities {
    final int maxWidth;
    final int maxHeight;

    MediaCapabilities(int maxWidth, int maxHeight) {
        this.maxWidth = Math.max(0, maxWidth);
        this.maxHeight = Math.max(0, maxHeight);
    }
}
