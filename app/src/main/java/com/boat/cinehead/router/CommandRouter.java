package com.boat.cinehead.router;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CommandRouter {
    public static final int SCHEMA_VERSION = 1;
    private static final int MAX_INPUT_LENGTH = 500;
    private static final int MAX_QUERY_LENGTH = 300;
    private static final int MINIMUM_MARGIN = 1;
    private static final String[] CANONICAL = {
        "volume", "percent", "percentage", "settings", "bluetooth", "android", "wifi",
        "open", "launch", "maximum", "mute", "half", "search", "find", "look",
        "youtube", "hotstar", "netflix", "amazon", "prime", "video", "application"
    };
    private static final List<String> BANNED_VOLUME = Arrays.asList(
        "increase", "decrease", "up", "down", "playback", "music", "full", "by",
        "between", "from", "or", "louder", "reduce"
    );
    private static final List<String> CONTROL_WORDS = Arrays.asList(
        "volume","percent","percentage","settings","bluetooth","android","wifi","open","launch",
        "maximum","mute","half","search","find","look","youtube","hotstar","netflix","amazon",
        "prime","video","application","bt","yt","jio","jiohotstar","you","tube","net","flix",
        "wi","fi","set","the","to","at","as","make","audio","for","on","in","using","go",
        "take","me","and","then","app","page","menu","screen","up","straight","directly",
        "please","can","could","would","i","want","hey","do","not","don","t","never",
        "except","without","is","available","support","play","watch","download","subscribe",
        "install","close","start","increase","decrease","down","playback","music","full","by",
        "between","from","or","louder","reduce","show","anything"
    );
    private static final List<String> APP_WORDS = Arrays.asList(
        "youtube","yt","netflix","hotstar","jiohotstar","amazon","prime"
    );

    private CommandRouter() {}

    public static final class Action {
        public final String type;
        public final String target;
        public final Map<String, Object> parameters;
        public final boolean requiresConfirmation;

        Action(String type, String target, Map<String, Object> parameters, boolean requiresConfirmation) {
            this.type = type;
            this.target = target;
            this.parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
            this.requiresConfirmation = requiresConfirmation;
        }
    }

    public static final class Result {
        public final int schemaVersion;
        public final String modelVersion;
        public final int id;
        public final String command;
        public final String status;
        public final Map<String, Object> slots;
        public final List<Action> actions;
        public final Integer value;
        public final String platform;
        public final String query;
        public final String app;

        Result(int id, String command, Map<String, Object> slots, List<Action> actions) {
            this.schemaVersion = SCHEMA_VERSION;
            this.modelVersion = RouterModel.MODEL_VERSION;
            this.id = id;
            this.command = command;
            this.status = id == 0 ? "NO_MATCH" : "MATCH";
            this.slots = Collections.unmodifiableMap(new LinkedHashMap<>(slots));
            this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
            this.value = slots.get("value") instanceof Integer ? (Integer) slots.get("value") : null;
            this.platform = slots.get("platform") instanceof String ? (String) slots.get("platform") : null;
            this.query = slots.get("query") instanceof String ? (String) slots.get("query") : null;
            this.app = slots.get("app") instanceof String ? (String) slots.get("app") : null;
        }

        public String displayText() {
            StringBuilder text = new StringBuilder();
            text.append("ID: ").append(id).append("\nCOMMAND: ").append(command);
            if (value != null) text.append("\nVALUE: ").append(value);
            if (app != null) text.append("\nAPP: ").append(app);
            if (platform != null) text.append("\nPLATFORM: ").append(platform);
            if (query != null) text.append("\nQUERY: ").append(query);
            if (!actions.isEmpty()) {
                text.append("\nACTIONS:");
                for (int i = 0; i < actions.size(); i++) {
                    Action action = actions.get(i);
                    text.append("\n").append(i + 1).append(". ").append(action.type);
                    if (action.target != null) text.append(" -> ").append(action.target);
                }
            }
            return text.toString();
        }
    }

    private static final class Token {
        final String word;
        final int start;
        final int end;
        Token(String word, int start, int end) { this.word = word; this.start = start; this.end = end; }
    }

    private static final class Span {
        final int end;
        final String value;
        Span(int end, String value) { this.end = end; this.value = value; }
    }

    private static final class SearchRequest {
        final String platform;
        final String query;
        SearchRequest(String platform, String query) { this.platform = platform; this.query = query; }
    }

    private static final class Classification {
        final int intent;
        final int margin;
        Classification(int intent, int margin) { this.intent = intent; this.margin = margin; }
    }

    public static Result route(String input) {
        if (input == null || input.trim().isEmpty() || input.length() > MAX_INPUT_LENGTH) return noMatch();
        List<Token> tokens = tokenize(input);
        if (tokens.isEmpty() || isNegated(tokens) || isCapabilityQuestion(tokens) || hasUnsupportedAction(tokens)) return noMatch();
        List<String> words = words(tokens);
        Classification classification = classify(words);
        if (classification.margin < MINIMUM_MARGIN) return noMatch();

        if (classification.intent == 1) {
            Integer volume = parseVolume(input, words);
            if (volume != null) return volumeResult(volume);
        } else if (classification.intent >= 2 && classification.intent <= 4) {
            if (validSettings(words, classification.intent)) return simpleResult(classification.intent);
        } else if (classification.intent == 5) {
            SearchRequest request = parseSearch(input, tokens);
            if (request != null) return searchResult(request);
        } else if (classification.intent == 6) {
            String app = parseOpenApp(tokens);
            if (app != null) return openAppResult(app);
        }
        return noMatch();
    }

    static String debugClassification(String input) {
        Classification classification = classify(words(tokenize(input)));
        return RouterModel.LABELS[classification.intent] + ":" + classification.margin;
    }

    private static Result noMatch() {
        return new Result(0, "NO_MATCH", Collections.emptyMap(), Collections.emptyList());
    }

    private static Result simpleResult(int id) {
        return new Result(id, RouterModel.LABELS[id], Collections.emptyMap(),
            Collections.singletonList(action(RouterModel.LABELS[id], null, Collections.emptyMap())));
    }

    private static Result volumeResult(int value) {
        Map<String, Object> slots = mapOf("value", value, "unit", "PERCENT");
        return new Result(1, "SET_VOLUME", slots,
            Collections.singletonList(action("SET_VOLUME", null, slots)));
    }

    private static Result openAppResult(String app) {
        Map<String, Object> slots = mapOf("app", app);
        return new Result(6, "OPEN_APP", slots,
            Collections.singletonList(action("OPEN_APP", app, Collections.emptyMap())));
    }

    private static Result searchResult(SearchRequest request) {
        Map<String, Object> slots = mapOf("platform", request.platform, "query", request.query);
        List<Action> actions = new ArrayList<>();
        if (!request.platform.equals("CURRENT_APP")) actions.add(action("OPEN_APP", request.platform, Collections.emptyMap()));
        actions.add(action("SEARCH", request.platform, mapOf("query", request.query)));
        return new Result(5, "SEARCH", slots, actions);
    }

    private static Action action(String type, String target, Map<String, Object> parameters) {
        return new Action(type, target, parameters, false);
    }

    private static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) map.put((String) values[i], values[i + 1]);
        return map;
    }

    private static SearchRequest parseSearch(String input, List<Token> tokens) {
        int begin = skipPolitePrefix(tokens, 0);
        int end = skipPoliteSuffix(tokens, tokens.size());

        int openEnd = openActionEnd(tokens, begin);
        if (openEnd >= 0) {
            Span platform = platformAt(tokens, openEnd);
            if (platform != null) {
                int cursor = platform.end;
                if (cursor < end && is(tokens, cursor, "app", "application")) cursor++;
                if (cursor < end && is(tokens, cursor, "and", "then")) cursor++;
                int searchEnd = searchActionEnd(tokens, cursor);
                if (searchEnd >= 0 && searchEnd < end) {
                    String query = extract(input, tokens, searchEnd, end);
                    return validQuery(query) ? new SearchRequest(platform.value, query) : null;
                }
            }
        }

        Span leadingPlatform = platformAt(tokens, begin);
        if (leadingPlatform != null) {
            int searchEnd = searchActionEnd(tokens, leadingPlatform.end);
            if (searchEnd >= 0 && searchEnd < end) {
                String query = extract(input, tokens, searchEnd, end);
                return validQuery(query) ? new SearchRequest(leadingPlatform.value, query) : null;
            }
        }

        int searchEnd = searchActionEnd(tokens, begin);
        if (searchEnd < 0 || searchEnd >= end) return null;
        for (int prep = end - 1; prep >= searchEnd; prep--) {
            if (!is(tokens, prep, "on", "in", "using")) continue;
            Span platform = platformAt(tokens, prep + 1);
            if (platform != null && platform.end == end) {
                if (containsConjoinedPlatforms(tokens, searchEnd, end)) return null;
                String query = extract(input, tokens, searchEnd, prep);
                return validQuery(query) ? new SearchRequest(platform.value, query) : null;
            }
        }
        String query = extract(input, tokens, searchEnd, end);
        return validQuery(query) ? new SearchRequest("CURRENT_APP", query) : null;
    }

    private static String parseOpenApp(List<Token> tokens) {
        int begin = skipPolitePrefix(tokens, 0);
        int end = skipPoliteSuffix(tokens, tokens.size());
        int cursor = openActionEnd(tokens, begin);
        if (cursor < 0) return null;
        if (cursor < end && is(tokens, cursor, "the")) cursor++;
        Span platform = platformAt(tokens, cursor);
        if (platform == null) return null;
        cursor = platform.end;
        if (cursor < end && is(tokens, cursor, "app", "application")) cursor++;
        return cursor == end ? platform.value : null;
    }

    private static int openActionEnd(List<Token> tokens, int start) {
        if (is(tokens, start, "open", "launch")) return start + 1;
        if (tokenMatches(tokens, start, "go", "to")) return start + 2;
        if (tokenMatches(tokens, start, "take", "me", "to")) return start + 3;
        return -1;
    }

    private static int searchActionEnd(List<Token> tokens, int start) {
        if (is(tokens, start, "search")) return start + (is(tokens, start + 1, "for") ? 2 : 1);
        if (is(tokens, start, "find")) return start + 1;
        if (tokenMatches(tokens, start, "look", "for")) return start + 2;
        return -1;
    }

    private static Span platformAt(List<Token> tokens, int index) {
        if (is(tokens, index, "youtube", "yt")) return new Span(index + 1, "YOUTUBE");
        if (tokenMatches(tokens, index, "you", "tube")) return new Span(index + 2, "YOUTUBE");
        if (is(tokens, index, "netflix")) return new Span(index + 1, "NETFLIX");
        if (tokenMatches(tokens, index, "net", "flix")) return new Span(index + 2, "NETFLIX");
        if (is(tokens, index, "jiohotstar", "hotstar")) return new Span(index + 1, "JIO_HOTSTAR");
        if (tokenMatches(tokens, index, "jio", "hotstar")) return new Span(index + 2, "JIO_HOTSTAR");
        if (is(tokens, index, "amazon")) {
            int end = index + 1;
            if (is(tokens, end, "prime")) end++;
            if (is(tokens, end, "video")) end++;
            return new Span(end, "AMAZON_PRIME_VIDEO");
        }
        if (is(tokens, index, "prime")) return new Span(index + (is(tokens, index + 1, "video") ? 2 : 1), "AMAZON_PRIME_VIDEO");
        return null;
    }

    private static boolean containsConjoinedPlatforms(List<Token> tokens, int begin, int end) {
        for (int i = begin; i < end; i++) {
            Span first = platformAt(tokens, i);
            if (first == null) continue;
            if (first.end < end && is(tokens, first.end, "and", "or") && platformAt(tokens, first.end + 1) != null) return true;
            i = first.end - 1;
        }
        return false;
    }

    private static boolean validQuery(String query) {
        return query != null && !query.isEmpty() && query.length() <= MAX_QUERY_LENGTH;
    }

    private static String extract(String input, List<Token> tokens, int begin, int end) {
        if (begin >= end || begin < 0 || end > tokens.size()) return null;
        String value = input.substring(tokens.get(begin).start, tokens.get(end - 1).end).trim();
        return value.replaceFirst("[.!?]+$", "").trim();
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
            if (word.equals("percent") || word.equals("percentage")) continue;
            int value = -1;
            if (word.matches("\\d+")) {
                try { value = Integer.parseInt(word); } catch (NumberFormatException ignored) { return null; }
            } else if (word.equals("one") && i + 1 < words.size() && words.get(i + 1).equals("hundred")) {
                value = 100; i++;
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

    private static boolean validSettings(List<String> words, int intent) {
        boolean action = words.contains("open") || words.contains("launch") || words.contains("go") || words.contains("take");
        if (!action || !words.contains("settings")) return false;
        if (intent == 2) return words.contains("bluetooth") || words.contains("bt");
        if (intent == 3) return words.contains("android");
        return intent == 4 && words.contains("wifi");
    }

    private static boolean isNegated(List<Token> tokens) {
        List<String> words = words(tokens);
        return words.contains("never") || words.contains("except") || wordMatches(words, 0, "do", "not") ||
            wordMatches(words, 0, "don", "t") || words.contains("without");
    }

    private static boolean isCapabilityQuestion(List<Token> tokens) {
        List<String> words = words(tokens);
        if (wordMatches(words, 0, "can", "i") || wordMatches(words, 0, "do", "you", "support")) return true;
        if (words.size() >= 2 && words.get(0).equals("is") && words.contains("available")) return true;
        return !words.isEmpty() && words.get(0).equals("can") && words.size() > 1 && !words.get(1).equals("you");
    }

    private static boolean hasUnsupportedAction(List<Token> tokens) {
        List<String> words = words(tokens);
        int begin = skipPolitePrefix(tokens, 0);
        if (begin < words.size() && Arrays.asList("play", "watch", "download", "subscribe", "install", "close", "start").contains(words.get(begin))) return true;
        for (int i = 0; i + 1 < words.size(); i++) {
            if ((words.get(i).equals("and") || words.get(i).equals("then")) &&
                Arrays.asList("play", "watch", "download", "subscribe", "install", "close", "set", "mute").contains(words.get(i + 1))) return true;
        }
        return false;
    }

    private static int skipPolitePrefix(List<Token> tokens, int start) {
        List<String> words = words(tokens);
        if (wordMatches(words, start, "would", "you", "please")) return start + 3;
        if (wordMatches(words, start, "i", "want", "you", "to")) return start + 4;
        if (wordMatches(words, start, "can", "you") || wordMatches(words, start, "could", "you")) return start + 2;
        if (wordMatches(words, start, "hey", "please")) return start + 2;
        if (is(tokens, start, "please", "hey")) return start + 1;
        return start;
    }

    private static int skipPoliteSuffix(List<Token> tokens, int end) {
        return end > 0 && is(tokens, end - 1, "please") ? end - 1 : end;
    }

    private static Classification classify(List<String> words) {
        List<String> modelWords = new ArrayList<>(words.size());
        for (String word : words) {
            if (APP_WORDS.contains(word)) modelWords.add("__app__");
            else if (word.matches("\\d+")) modelWords.add("__number__");
            else if (CONTROL_WORDS.contains(word) || basicNumber(word) >= 0 || word.equals("hundred")) modelWords.add(word);
            else modelWords.add("__content__");
        }
        int[] features = new int[RouterModel.FEATURES];
        for (int i = 0; i < modelWords.size(); i++) {
            addFeature(features, fnv("u:" + modelWords.get(i)));
            if (i + 1 < modelWords.size()) addFeature(features, fnv("b:" + modelWords.get(i) + "|" + modelWords.get(i + 1)));
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

    private static List<Token> tokenize(String input) {
        Matcher matcher = Pattern.compile("[A-Za-z0-9]+").matcher(input);
        List<Token> tokens = new ArrayList<>();
        while (matcher.find()) tokens.add(new Token(canonicalize(matcher.group().toLowerCase(Locale.ROOT)), matcher.start(), matcher.end()));
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if (tokens.get(i).word.equals("wi") && tokens.get(i + 1).word.equals("fi")) {
                Token joined = new Token("wifi", tokens.get(i).start, tokens.get(i + 1).end);
                tokens.set(i, joined);
                tokens.remove(i + 1);
            }
        }
        return tokens;
    }

    private static String canonicalize(String word) {
        if (word.equals("yt") || word.equals("bt") || word.equals("jiohotstar")) return word;
        String best = word;
        int bestDistance = Integer.MAX_VALUE;
        for (String known : CANONICAL) {
            int distance = editDistance(word, known);
            int limit = known.length() <= 5 ? 1 : 2;
            if (distance <= limit && distance < bestDistance) { best = known; bestDistance = distance; }
        }
        return best;
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

    private static List<String> words(List<Token> tokens) {
        List<String> words = new ArrayList<>(tokens.size());
        for (Token token : tokens) words.add(token.word);
        return words;
    }

    private static boolean is(List<Token> tokens, int index, String... choices) {
        if (index < 0 || index >= tokens.size()) return false;
        for (String choice : choices) if (tokens.get(index).word.equals(choice)) return true;
        return false;
    }

    private static boolean tokenMatches(List<Token> tokens, int start, String... expected) {
        if (start < 0 || start + expected.length > tokens.size()) return false;
        for (int i = 0; i < expected.length; i++) if (!tokens.get(start + i).word.equals(expected[i])) return false;
        return true;
    }

    private static boolean wordMatches(List<String> words, int start, String... expected) {
        if (start < 0 || start + expected.length > words.size()) return false;
        for (int i = 0; i < expected.length; i++) if (!words.get(start + i).equals(expected[i])) return false;
        return true;
    }
}
