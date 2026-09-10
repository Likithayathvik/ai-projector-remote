package com.boat.cinehead.router;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CommandRouter {
    private static final String[] CANONICAL = {
        "volume", "settings", "bluetooth", "android", "wifi", "open", "launch",
        "maximum", "mute", "half", "search", "find", "look", "youtube", "hotstar",
        "netflix", "amazon", "prime", "video"
    };
    private static final List<String> BANNED_VOLUME = Arrays.asList(
        "increase", "decrease", "up", "down", "playback", "music", "full", "by"
    );

    private CommandRouter() {}

    public static final class Result {
        public final int id;
        public final String command;
        public final Integer value;
        public final String platform;
        public final String query;
        Result(int id, String command, Integer value) {
            this(id, command, value, null, null);
        }
        Result(int id, String command, Integer value, String platform, String query) {
            this.id = id; this.command = command; this.value = value;
            this.platform = platform; this.query = query;
        }
        public String displayText() {
            return "ID: " + id + "\nCOMMAND: " + command +
                (value == null ? "" : "\nVALUE: " + value) +
                (platform == null ? "" : "\nPLATFORM: " + platform) +
                (query == null ? "" : "\nQUERY: " + query);
        }
    }

    public static Result route(String input) {
        if (input == null || input.trim().isEmpty()) return noMatch();
        String raw = input.toLowerCase(Locale.ROOT);
        List<String> words = normalize(raw);
        Classification classification = classify(words);
        if (classification.margin < 18) return noMatch();
        if (classification.intent == 1) {
            Integer volume = parseVolume(raw, words);
            if (volume != null) return new Result(1, RouterModel.LABELS[1], volume);
        } else if (classification.intent == 2 && validSettings(words, "bluetooth", "bt")) {
            return new Result(2, RouterModel.LABELS[2], null);
        } else if (classification.intent == 3 && validSettings(words, "android")) {
            return new Result(3, RouterModel.LABELS[3], null);
        } else if (classification.intent == 4 && validSettings(words, "wifi")) {
            return new Result(4, RouterModel.LABELS[4], null);
        } else if (classification.intent == 5) {
            SearchRequest request = parseSearch(input);
            if (request != null) return new Result(5, RouterModel.LABELS[5], null, request.platform, request.query);
        }
        return noMatch();
    }

    private static Result noMatch() { return new Result(0, RouterModel.LABELS[0], null); }

    private static final String PLATFORM_PATTERN =
        "amazon\\s+prime(?:\\s+video)?|prime\\s+video|jio\\s*hotstar|you\\s*tube|net\\s*flix|amazon|prime|hotstar|youtube|netflix";

    private static final class SearchRequest {
        final String platform;
        final String query;
        SearchRequest(String platform, String query) { this.platform = platform; this.query = query; }
    }

    private static SearchRequest parseSearch(String input) {
        Pattern actionFirst = Pattern.compile(
            "^\\s*(?:search(?:\\s+for)?|find|look\\s+for)\\s+(.+?)\\s+(?:in|on)\\s+(" + PLATFORM_PATTERN + ")\\s*[.!?]*\\s*$",
            Pattern.CASE_INSENSITIVE);
        Pattern platformFirst = Pattern.compile(
            "^\\s*(" + PLATFORM_PATTERN + ")\\s+(?:search(?:\\s+for)?|find|look\\s+for)\\s+(.+?)\\s*[.!?]*\\s*$",
            Pattern.CASE_INSENSITIVE);
        Matcher match = actionFirst.matcher(input);
        String platform;
        String query;
        if (match.matches()) {
            query = match.group(1).trim();
            platform = match.group(2);
        } else {
            match = platformFirst.matcher(input);
            if (!match.matches()) return null;
            platform = match.group(1);
            query = match.group(2).trim();
        }
        query = query.replaceFirst("[.!?]+$", "").trim();
        if (query.isEmpty()) return null;
        return new SearchRequest(canonicalPlatform(platform), query);
    }

    private static String canonicalPlatform(String platform) {
        String compact = platform.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        if (compact.equals("youtube")) return "YOUTUBE";
        if (compact.equals("jiohotstar") || compact.equals("hotstar")) return "JIO_HOTSTAR";
        if (compact.equals("netflix")) return "NETFLIX";
        return "AMAZON_PRIME_VIDEO";
    }

    private static final class Classification {
        final int intent;
        final int margin;
        Classification(int intent, int margin) { this.intent = intent; this.margin = margin; }
    }

    private static Classification classify(List<String> words) {
        int[] features = new int[RouterModel.FEATURES];
        for (int i = 0; i < words.size(); i++) {
            addFeature(features, fnv("u:" + words.get(i)));
            if (i + 1 < words.size()) addFeature(features, fnv("b:" + words.get(i) + "|" + words.get(i + 1)));
        }
        int[] scores = new int[RouterModel.LABELS.length];
        int best = 0;
        for (int c = 0; c < scores.length; c++) {
            int score = RouterModel.BIAS[c];
            for (int i = 0; i < features.length; i++) score += RouterModel.WEIGHTS[c][i] * features[i];
            scores[c] = score;
            if (scores[c] > scores[best]) best = c;
        }
        int second = Integer.MIN_VALUE;
        for (int c = 0; c < scores.length; c++) if (c != best) second = Math.max(second, scores[c]);
        return new Classification(best, scores[best] - second);
    }

    private static int fnv(String value) {
        int hash = 0x811c9dc5;
        for (int i = 0; i < value.length(); i++) hash = (hash ^ (value.charAt(i) & 0xff)) * 0x01000193;
        return hash;
    }

    private static void addFeature(int[] features, int hash) {
        int index = hash & (RouterModel.FEATURES - 1);
        features[index] = Math.max(-127, Math.min(127, features[index] + ((hash & 0x100) != 0 ? 1 : -1)));
    }

    private static Integer parseVolume(String raw, List<String> words) {
        if (!words.contains("volume") && !words.contains("mute") && !words.contains("half") && !words.contains("maximum")) return null;
        for (String banned : BANNED_VOLUME) if (words.contains(banned)) return null;
        if (Pattern.compile("-\\s*\\d|\\d\\s*\\.\\s*\\d").matcher(raw).find()) return null;
        if (words.contains("mute")) return 0;
        if (words.contains("half")) return 50;
        if (words.contains("maximum")) return 100;

        int found = -1;
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            int value = -1;
            if (word.matches("\\d+")) {
                try { value = Integer.parseInt(word); } catch (NumberFormatException ignored) { return null; }
            } else if (word.equals("one") && i + 1 < words.size() && words.get(i + 1).equals("hundred")) {
                value = 100;
                i++;
            } else {
                value = basicNumber(word);
                if (value >= 20 && value < 100 && value % 10 == 0 && i + 1 < words.size()) {
                    int units = basicNumber(words.get(i + 1));
                    if (units >= 1 && units <= 9) { value += units; i++; }
                }
            }
            if (value >= 0) {
                if (found >= 0) return null;
                found = value;
            }
        }
        return found >= 0 && found <= 100 && words.contains("volume") ? found : null;
    }

    private static int basicNumber(String word) {
        String[] ones = {"zero","one","two","three","four","five","six","seven","eight","nine","ten","eleven","twelve","thirteen","fourteen","fifteen","sixteen","seventeen","eighteen","nineteen"};
        String[] tens = {"twenty","thirty","forty","fifty","sixty","seventy","eighty","ninety"};
        for (int i = 0; i < ones.length; i++) if (word.equals(ones[i])) return i;
        for (int i = 0; i < tens.length; i++) if (word.equals(tens[i])) return (i + 2) * 10;
        return -1;
    }

    private static boolean validSettings(List<String> words, String... entities) {
        boolean action = words.contains("open") || words.contains("launch") || words.contains("go");
        boolean entity = false;
        for (String candidate : entities) entity |= words.contains(candidate);
        return action && entity && words.contains("settings");
    }

    private static List<String> normalize(String raw) {
        raw = raw.replace("wi-fi", "wifi");
        Matcher matcher = Pattern.compile("[a-z0-9]+").matcher(raw);
        List<String> result = new ArrayList<>();
        while (matcher.find()) {
            String word = matcher.group();
            String best = word;
            int bestDistance = Integer.MAX_VALUE;
            for (String known : CANONICAL) {
                int distance = editDistance(word, known);
                int limit = known.length() <= 5 ? 1 : 2;
                if (distance <= limit && distance < bestDistance) {
                    best = known; bestDistance = distance;
                }
            }
            result.add(best);
        }
        return result;
    }

    private static int editDistance(String a, String b) {
        int[] row = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) row[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int previous = row[0]; row[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int old = row[j];
                row[j] = Math.min(Math.min(row[j] + 1, row[j - 1] + 1), previous + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
                previous = old;
            }
        }
        return row[b.length()];
    }
}
