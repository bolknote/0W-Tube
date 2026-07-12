package ru.tubetv.app;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class VideoRanker {
    private static final String[] UNWANTED = {"трейлер", "обзор", "фрагмент", "отрывок", "тизер"};

    static Comparator<VideoItem> comparator(String query) {
        return (left, right) -> {
            int relevance = Integer.compare(score(right.title, query), score(left.title, query));
            if (relevance != 0) return relevance;
            int quality = Integer.compare(right.maxWidth, left.maxWidth);
            if (quality != 0) return quality;
            quality = Integer.compare(right.maxHeight, left.maxHeight);
            if (quality != 0) return quality;
            return 0; // List.sort is stable: preserve the source's own ranking.
        };
    }

    static int score(String title, String query) {
        String normalizedTitle = normalize(title);
        String normalizedQuery = normalize(query);
        if (normalizedTitle.isEmpty() || normalizedQuery.isEmpty()) return 0;
        int score = 0;
        if (normalizedTitle.equals(normalizedQuery)) score += 100_000;
        else if (normalizedTitle.startsWith(normalizedQuery)) score += 35_000;
        else if (normalizedTitle.contains(normalizedQuery)) score += 25_000;

        String[] queryWords = normalizedQuery.split(" ");
        String[] titleWords = normalizedTitle.split(" ");
        Set<String> titleSet = new HashSet<>();
        for (String word : titleWords) if (!word.isEmpty()) titleSet.add(word);
        int matched = 0;
        for (String word : queryWords) {
            if (!word.isEmpty() && titleSet.contains(word)) matched++;
        }
        score += matched * 5_000;
        if (matched == queryWords.length) score += 10_000;
        score -= Math.max(0, titleWords.length - queryWords.length) * 60;
        for (String word : UNWANTED) {
            if (normalizedTitle.contains(word) && !normalizedQuery.contains(word)) score -= 3_000;
        }
        return score;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        value = value.toLowerCase(Locale.ROOT).replace('ё', 'е');
        StringBuilder result = new StringBuilder(value.length());
        boolean space = true;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isLetterOrDigit(current)) {
                result.append(current);
                space = false;
            } else if (!space) {
                result.append(' ');
                space = true;
            }
        }
        int length = result.length();
        if (length > 0 && result.charAt(length - 1) == ' ') result.setLength(length - 1);
        return result.toString();
    }

    private VideoRanker() { }
}
