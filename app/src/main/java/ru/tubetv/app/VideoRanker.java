package ru.tubetv.app;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class VideoRanker {
    private static final String[] UNWANTED = {"трейлер", "обзор", "фрагмент", "отрывок", "тизер"};

    static Comparator<VideoItem> comparator(String query) {
        return comparator(query, false);
    }

    static Comparator<VideoItem> comparator(String query, boolean lowestQualityFirst) {
        Map<String, Integer> scores = new HashMap<>();
        return (left, right) -> {
            int leftScore = cachedScore(scores, left.title, query);
            int rightScore = cachedScore(scores, right.title, query);
            int relevance = Integer.compare(rightScore, leftScore);
            if (relevance != 0) return relevance;
            int quality = lowestQualityFirst
                    ? compareKnownQuality(left.maxWidth, right.maxWidth)
                    : Integer.compare(right.maxWidth, left.maxWidth);
            if (quality != 0) return quality;
            quality = lowestQualityFirst
                    ? compareKnownQuality(left.maxHeight, right.maxHeight)
                    : Integer.compare(right.maxHeight, left.maxHeight);
            if (quality != 0) return quality;
            return 0; // List.sort is stable: preserve the source's own ranking.
        };
    }

    private static int cachedScore(Map<String, Integer> scores, String title, String query) {
        Integer cached = scores.get(title);
        if (cached != null) return cached;
        int calculated = score(title, query);
        scores.put(title, calculated);
        return calculated;
    }

    private static int compareKnownQuality(int left, int right) {
        if (left == 0) return right == 0 ? 0 : 1;
        if (right == 0) return -1;
        return Integer.compare(left, right);
    }

    static int score(String title, String query) {
        String normalizedTitle = normalize(title);
        String normalizedQuery = normalize(query);
        if (normalizedTitle.isEmpty() || normalizedQuery.isEmpty()) return 0;
        int nativeScore = scoreNormalized(normalizedTitle, normalizedQuery);
        String latinTitle = PeerTubeClient.transliterate(normalizedTitle);
        String latinQuery = PeerTubeClient.transliterate(normalizedQuery);
        int latinScore = scoreNormalized(latinTitle, latinQuery);
        if (fuzzyPhrase(latinTitle, latinQuery)) {
            latinScore = Math.max(latinScore, latinTitle.split(" ").length
                    == latinQuery.split(" ").length ? 90_000 : 34_000);
        }
        return Math.max(nativeScore, latinScore);
    }

    private static int scoreNormalized(String normalizedTitle, String normalizedQuery) {
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

    private static boolean fuzzyPhrase(String title, String query) {
        String[] titleWords = title.split(" ");
        String[] queryWords = query.split(" ");
        if (queryWords.length == 0 || queryWords.length > titleWords.length) return false;
        for (int i = 0; i < queryWords.length; i++) {
            if (!fuzzyToken(queryWords[i], titleWords[i])) return false;
        }
        return true;
    }

    static boolean fuzzyToken(String left, String right) {
        if (left.equals(right)) return true;
        int shortest = Math.min(left.length(), right.length());
        if (shortest < 5) return false;
        int allowed = Math.max(left.length(), right.length()) <= 8 ? 1 : 2;
        if (Math.abs(left.length() - right.length()) > allowed) return false;
        return editDistance(left, right) <= allowed;
    }

    static int editDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int i = 0; i < previous.length; i++) previous[i] = i;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int substitution = previous[j - 1]
                        + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(current[j - 1] + 1,
                        previous[j] + 1), substitution);
            }
            previous = current;
        }
        return previous[right.length()];
    }

    static String normalize(String value) {
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
