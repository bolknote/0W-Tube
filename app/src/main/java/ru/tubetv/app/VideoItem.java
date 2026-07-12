package ru.tubetv.app;

final class VideoItem {
    final String source;
    final String title;
    final String subtitle;
    final String thumbnail;
    final String playUrl;
    final String pageUrl;
    final long durationMs;
    final int maxWidth;
    final int maxHeight;

    VideoItem(String source, String title, String subtitle, String thumbnail,
              String playUrl, String pageUrl) {
        this(source, title, subtitle, thumbnail, playUrl, pageUrl, 0L);
    }

    VideoItem(String source, String title, String subtitle, String thumbnail,
              String playUrl, String pageUrl, long durationMs) {
        this.source = source;
        this.title = title;
        this.subtitle = subtitle;
        this.thumbnail = thumbnail;
        this.playUrl = playUrl;
        this.pageUrl = pageUrl;
        this.durationMs = Math.max(0L, durationMs);
        this.maxWidth = 0;
        this.maxHeight = 0;
    }

    private VideoItem(String source, String title, String subtitle, String thumbnail,
                      String playUrl, String pageUrl, long durationMs, int maxWidth, int maxHeight) {
        this.source = source;
        this.title = title;
        this.subtitle = subtitle;
        this.thumbnail = thumbnail;
        this.playUrl = playUrl;
        this.pageUrl = pageUrl;
        this.durationMs = Math.max(0L, durationMs);
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
    }

    VideoItem withQuality(int width, int height) {
        String quality = width >= 3840 ? "4K" : width >= 2560 ? "1440p"
                : width >= 1920 ? "1080p" : width >= 1280 ? "720p" : width > 0 ? width + "px" : "";
        String details = quality.isEmpty() ? subtitle : subtitle + "  •  " + quality;
        return new VideoItem(source, title, details, thumbnail, playUrl, pageUrl, durationMs, width, height);
    }
}
