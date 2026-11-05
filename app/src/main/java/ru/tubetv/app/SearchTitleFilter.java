package ru.tubetv.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Query-local lexical gate, not a film classifier: duration and genre are irrelevant. */
final class SearchTitleFilter {
    private static final Set<String> IGNORE = new HashSet<>(Arrays.asList(
            "а", "и", "в", "во", "на", "с", "со", "из", "за", "к", "ко", "по", "о", "об",
            "от", "до", "у", "для", "про", "без", "через", "a", "an", "and", "the", "of", "to",
            "in", "on", "for", "with", "фильм", "кино", "смотреть", "онлайн", "film", "movie",
            "online", "full", "hd", "fhd", "uhd", "fullhd", "bdrip", "brrip", "bluray", "rip",
            "sub", "subs", "rus", "eng", "субтитры", "озвучка", "дубляж"));
    private static final String[] ENDINGS = {"иями", "ями", "ами", "его", "ого", "ему", "ому",
            "ыми", "ими", "ией", "иям", "иях", "ой", "ий", "ый", "ая", "яя", "ое", "ее",
            "ые", "ие", "ов", "ев", "ей", "ам", "ям", "ах", "ях", "ом", "ем", "ия", "ию",
            "ии", "ы", "и", "а", "я", "у", "ю", "е", "о", "ь"};
    private static final String[] ROMAN = {"i", "ii", "iii", "iv", "v", "vi", "vii", "viii",
            "ix", "x", "xi", "xii", "xiii", "xiv", "xv", "xvi", "xvii", "xviii", "xix", "xx"};

    private final Tokens query;

    SearchTitleFilter(String query) { this.query = tokens(query); }

    boolean accepts(String title) {
        if (query.words.isEmpty() && query.numbers.isEmpty()) return true;
        Tokens candidate = tokens(title);
        if (!matchesNumbers(candidate)) return false;
        if (query.words.isEmpty()) return true;
        if (candidate.words.isEmpty()) return false;

        // Joined names such as «КинДзаДза» should survive punctuation normalization.
        if (query.wordCount > 1) {
            for (Word word : candidate.words) if (word.latin.equals(query.compact)) return true;
        }
        int matched = 0;
        boolean[] used = new boolean[candidate.words.size()];
        for (Word word : query.words) {
            int found = find(word, candidate.words, used, false);
            if (found < 0) found = find(word, candidate.words, used, true);
            if (found >= 0) { used[found] = true; matched++; }
        }
        int required = (query.words.size() * 2 + 2) / 3;
        if (matched >= required) return true;

        // Alternative translations may share only one word (a short primary title,
        // not a long headline that happens to contain one query token).
        if (query.words.size() == 2) {
            Tokens primary = tokens(primaryTitle(title));
            if (primary.words.size() <= 2 && hasExactAnchor(primary.words, query.words)) return true;
        }

        // A query may add an author: «Солярис Тарковский» must keep «Солярис (1972)».
        // Extra unrelated title words do not qualify for this shortened-title exception.
        for (Word word : candidate.words) {
            if (find(word, query.words, null, false) < 0
                    && find(word, query.words, null, true) < 0) return false;
        }
        for (Word word : query.words) {
            if (word.length >= 5 && (find(word, candidate.words, null, false) >= 0
                    || find(word, candidate.words, null, true) >= 0)) return true;
        }
        return false;
    }

    private static boolean hasExactAnchor(List<Word> title, List<Word> query) {
        for (Word word : title) for (Word part : query) {
            if (part.length >= 5 && word.latin.equals(part.latin)) return true;
        }
        return false;
    }

    private static String primaryTitle(String title) {
        if (title == null) return "";
        int end = title.length();
        for (char separator : new char[]{'/', '|', '(', '[', '.'}) {
            int at = title.indexOf(separator);
            if (at > 0) end = Math.min(end, at);
        }
        return title.substring(0, end);
    }

    private boolean matchesNumbers(Tokens title) {
        for (String number : query.numbers) {
            if (title.numbers.contains(number)) continue;
            // A missing release year is not evidence of a different film. A conflicting
            // explicit year, or a missing/mismatching part number, is evidence.
            if (isYear(number) && !query.words.isEmpty()) {
                boolean hasYear = false;
                for (String other : title.numbers) if (isYear(other)) hasYear = true;
                if (!hasYear) continue;
            }
            return false;
        }
        return true;
    }

    private static int find(Word query, List<Word> title, boolean[] used, boolean fuzzy) {
        for (int i = 0; i < title.size(); i++) {
            if (used != null && used[i]) continue;
            Word word = title.get(i);
            if (fuzzy ? similar(query, word)
                    : query.latin.equals(word.latin) || query.stem.equals(word.stem)) return i;
        }
        return -1;
    }

    private static boolean similar(Word left, Word right) {
        if (VideoRanker.fuzzyToken(left.latin, right.latin)) return true;
        // Conventional Latin spellings can differ from direct transliteration in two
        // letters (Китон / Keaton, Моцарт / Mozart), without loosening Russian typos.
        return left.latinScript != right.latinScript
                && Math.min(left.latin.length(), right.latin.length()) >= 5
                && Math.abs(left.latin.length() - right.latin.length()) <= 2
                && VideoRanker.editDistance(left.latin, right.latin) <= 2;
    }

    private static Tokens tokens(String text) {
        List<String> raw = new ArrayList<>();
        List<String> meaningful = new ArrayList<>();
        for (String word : VideoRanker.normalize(text).split(" ")) {
            if (word.isEmpty() || qualityTag(word)) continue;
            raw.add(word);
            if (!IGNORE.contains(word)) meaningful.add(word);
        }
        if (meaningful.isEmpty()) meaningful = raw; // Titles can themselves be «На», «И», etc.
        Tokens result = new Tokens();
        Set<String> unique = new HashSet<>();
        StringBuilder compact = new StringBuilder();
        for (String token : meaningful) {
            int start = 0;
            for (int end = 1; end <= token.length(); end++) {
                if (end < token.length() && Character.isDigit(token.charAt(end - 1))
                        == Character.isDigit(token.charAt(end))) continue;
                String part = token.substring(start, end);
                start = end;
                String number = number(part);
                if (number != null) {
                    result.numbers.add(number);
                } else {
                    Word word = new Word(part);
                    compact.append(word.latin);
                    result.wordCount++;
                    if (unique.add(word.stem)) result.words.add(word);
                }
            }
        }
        result.compact = compact.toString();
        return result;
    }

    private static String number(String word) {
        for (int i = 0; i < ROMAN.length; i++) if (ROMAN[i].equals(word)) return Integer.toString(i + 1);
        for (int i = 0; i < word.length(); i++) if (!Character.isDigit(word.charAt(i))) return null;
        try { return Long.toString(Long.parseLong(word)); }
        catch (NumberFormatException ignored) { return word; }
    }

    private static boolean isYear(String value) {
        if (value.length() != 4) return false;
        int year = Integer.parseInt(value);
        return year >= 1900 && year <= 2099;
    }

    private static boolean qualityTag(String word) {
        if (word.equals("2k") || word.equals("4k") || word.equals("8k")
                || word.equals("2к") || word.equals("4к") || word.equals("8к")) return true;
        if (word.length() < 4) return false;
        char last = word.charAt(word.length() - 1);
        if (last != 'p' && last != 'р') return false;
        String size = word.substring(0, word.length() - 1);
        return size.equals("144") || size.equals("240") || size.equals("360") || size.equals("480")
                || size.equals("720") || size.equals("1080") || size.equals("1440") || size.equals("2160");
    }

    private static String stem(String word) {
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c < 'а' || c > 'я') return word;
        }
        for (String ending : ENDINGS) {
            if (word.endsWith(ending) && word.length() - ending.length() >= 3) {
                return word.substring(0, word.length() - ending.length());
            }
        }
        return word;
    }

    private static final class Word {
        final String latin;
        final String stem;
        final boolean latinScript;
        final int length;
        Word(String text) {
            length = text.length();
            latin = PeerTubeClient.transliterate(text);
            stem = PeerTubeClient.transliterate(SearchTitleFilter.stem(text));
            boolean latinOnly = true;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) < 'a' || text.charAt(i) > 'z') latinOnly = false;
            }
            latinScript = latinOnly;
        }
    }

    private static final class Tokens {
        final List<Word> words = new ArrayList<>();
        final Set<String> numbers = new LinkedHashSet<>();
        String compact;
        int wordCount;
    }
}
