package zelisline.ub.catalog.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tokenizes catalog search queries and ranks items by relevance so partial
 * names, abbreviations, typos, initials, and size variants still surface the
 * closest products.
 *
 * <p>Matching priority (highest first):
 * <ol>
 *   <li>Exact / phrase / SKU / barcode</li>
 *   <li>Alias / synonym / abbreviation</li>
 *   <li>Initials ({@code sl} → Supa Loaf)</li>
 *   <li>All tokens as word prefixes</li>
 *   <li>Contains / size-normalized tokens</li>
 *   <li>Significant tokens only (keeps results while typing)</li>
 *   <li>Fuzzy / edit-distance</li>
 * </ol>
 */
public final class CatalogSearchSupport {

    public static final int CANDIDATE_FETCH_SIZE = 500;
    private static final int MIN_FUZZY_PREFIX_LEN = 3;
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[\\s/_\\-]+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9.]+");
    private static final Pattern SIZE_TOKEN = Pattern.compile(
            "^(\\d+(?:\\.\\d+)?)(ml|l|litre|liter|g|gm|gram|grams|kg|kilogram|kilograms|pcs?|pack)?$");
    private static final Pattern SIZE_IN_TEXT = Pattern.compile(
            "(?i)(\\d+(?:\\.\\d+)?)\\s*(ml|l|litre|liter|g|gm|gram|grams|kg|kilogram|kilograms|pcs?|pack)?");

    /**
     * Built-in POS aliases / local names → expansion terms used for DB candidate
     * fetch and relevance scoring. Keep values as searchable catalog fragments.
     */
    private static final Map<String, List<String>> ALIASES = buildAliases();

    private CatalogSearchSupport() {
    }

    public record SearchableText(
            String name,
            String variantName,
            String sku,
            String barcode,
            String description,
            String brand,
            String size
    ) {
        public static SearchableText of(
                String name,
                String variantName,
                String sku,
                String barcode,
                String description
        ) {
            return of(name, variantName, sku, barcode, description, null, null);
        }

        public static SearchableText of(
                String name,
                String variantName,
                String sku,
                String barcode,
                String description,
                String brand,
                String size
        ) {
            return new SearchableText(name, variantName, sku, barcode, description, brand, size);
        }
    }

    public static List<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        String[] parts = TOKEN_SPLIT.split(normalized);
        List<String> tokens = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            String cleaned = NON_ALNUM.matcher(part).replaceAll("");
            if (!cleaned.isBlank()) {
                tokens.add(cleaned);
            }
        }
        return tokens;
    }

    /**
     * Longest token used for the first DB {@code LIKE} candidate fetch.
     */
    public static String candidateToken(String query) {
        List<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return null;
        }
        return tokens.stream()
                .max(Comparator.comparingInt(String::length))
                .orElse(tokens.get(0));
    }

    /**
     * Ordered DB candidate tokens: primary token, alias expansions, numeric size
     * fragments, then fuzzy prefixes. Callers should try these until ranking
     * returns hits.
     */
    public static List<String> dbCandidateTokens(String query) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String primary = candidateToken(query);
        if (primary != null) {
            out.add(primary);
        }
        for (String token : tokenize(query)) {
            List<String> expansions = ALIASES.get(token);
            if (expansions != null) {
                out.addAll(expansions);
            }
            String singular = singularize(token);
            if (!singular.equals(token)) {
                out.add(singular);
                List<String> singularExp = ALIASES.get(singular);
                if (singularExp != null) {
                    out.addAll(singularExp);
                }
            }
            String sizeKey = normalizeSizeToken(token);
            if (sizeKey != null) {
                // Prefer the numeric fragment for LIKE recall (500ml → 500).
                int colon = sizeKey.indexOf(':');
                if (colon > 0) {
                    out.add(sizeKey.substring(colon + 1));
                }
            }
        }
        out.addAll(fuzzyCandidateTokens(query));
        out.removeIf(t -> t == null || t.isBlank() || t.length() < 2);
        return List.copyOf(out);
    }

    /**
     * Progressively shorter prefixes used when the candidate token returns no rows.
     */
    public static List<String> fuzzyCandidateTokens(String query) {
        String primary = candidateToken(query);
        if (primary == null || primary.length() < 2) {
            return List.of();
        }
        List<String> prefixes = new ArrayList<>();
        int start = Math.min(MIN_FUZZY_PREFIX_LEN, primary.length() - 1);
        for (int len = start; len >= 2; len--) {
            String prefix = primary.substring(0, len);
            if (!prefix.equals(primary) && !prefixes.contains(prefix)) {
                prefixes.add(prefix);
            }
        }
        return prefixes;
    }

    public static boolean isBlankQuery(String query) {
        return query == null || query.isBlank() || tokenize(query).isEmpty();
    }

    /**
     * Relevance score; {@code 0} means the item should be excluded.
     */
    public static int score(SearchableText item, String query) {
        if (item == null || isBlankQuery(query)) {
            return 0;
        }
        List<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return 0;
        }

        String name = norm(item.name());
        String variant = norm(item.variantName());
        String sku = norm(item.sku());
        String barcode = norm(item.barcode());
        String description = norm(item.description());
        String brand = norm(item.brand());
        String size = norm(item.size());
        String phrase = String.join(" ", tokens);
        String compactPhrase = compact(phrase);

        String primaryHaystack = joinHaystack(name, variant, brand, size);
        String haystack = joinHaystack(name, variant, brand, size, sku, barcode, description);
        String[] primaryWords = splitWords(primaryHaystack);
        String[] words = splitWords(haystack);
        String initials = initialsOf(primaryWords);
        Set<String> itemSizeKeys = extractSizeKeys(joinHaystack(name, variant, size, description));

        int best = 0;

        if (!name.isBlank() && name.equals(phrase)) {
            best = Math.max(best, 50_000);
        }
        if (!brand.isBlank() && brand.equals(phrase)) {
            best = Math.max(best, 48_000);
        }
        if (!name.isBlank() && name.startsWith(phrase)) {
            best = Math.max(best, 45_000);
        }
        if (!brand.isBlank() && brand.startsWith(phrase)) {
            best = Math.max(best, 44_000);
        }
        if (primaryHaystack.contains(phrase)) {
            best = Math.max(best, 40_000 - Math.min(5_000, primaryHaystack.indexOf(phrase)));
        }
        if (!compactPhrase.isBlank() && compact(primaryHaystack).contains(compactPhrase)) {
            best = Math.max(best, 38_000);
        }
        if (!sku.isBlank() && (sku.equals(phrase) || sku.startsWith(phrase))) {
            best = Math.max(best, 42_000);
        }
        if (!barcode.isBlank() && (barcode.equals(phrase) || barcode.startsWith(phrase))) {
            best = Math.max(best, 42_000);
        }

        int aliasScore = aliasScore(tokens, primaryHaystack, primaryWords, brand, name);
        best = Math.max(best, aliasScore);

        if (!initials.isBlank() && compactPhrase.length() >= 2) {
            if (initials.equals(compactPhrase)) {
                best = Math.max(best, 36_000);
            } else if (initials.startsWith(compactPhrase)) {
                best = Math.max(best, 28_000);
            }
        }

        int prefixCost = greedyTokenCost(tokens, primaryWords, MatchMode.WORD_PREFIX);
        if (prefixCost >= 0) {
            best = Math.max(best, 30_000 - prefixCost);
        }

        int substringCost = greedyTokenCost(tokens, primaryWords, MatchMode.SUBSTRING);
        if (substringCost >= 0) {
            best = Math.max(best, 22_000 - substringCost);
        }

        int sizeScore = sizeMatchScore(tokens, itemSizeKeys);
        best = Math.max(best, sizeScore);

        // Ignore word order: same tokens in any order against primary words.
        if (tokens.size() > 1) {
            int unordered = greedyTokenCost(tokens, primaryWords, MatchMode.WORD_PREFIX);
            if (unordered >= 0) {
                best = Math.max(best, 29_000 - unordered);
            }
        }

        List<String> significant = expandSignificantTokens(tokens);
        if (!significant.isEmpty() && significant.size() <= tokens.size()) {
            boolean droppedShort = significant.size() < tokens.size();
            int sigPrefix = greedyTokenCost(significant, primaryWords, MatchMode.WORD_PREFIX);
            if (sigPrefix >= 0) {
                best = Math.max(best, (droppedShort ? 16_000 : 30_000) - sigPrefix);
            }
            int sigSub = greedyTokenCost(significant, primaryWords, MatchMode.SUBSTRING);
            if (sigSub >= 0) {
                best = Math.max(best, (droppedShort ? 14_000 : 22_000) - sigSub);
            }
        }

        if (prefixCost < 0 && substringCost < 0 && aliasScore == 0 && sizeScore == 0) {
            int skuPrefix = greedyTokenCost(tokens, words, MatchMode.WORD_PREFIX);
            if (skuPrefix >= 0) {
                best = Math.max(best, 18_000 - skuPrefix);
            }
            int skuSub = greedyTokenCost(tokens, words, MatchMode.SUBSTRING);
            if (skuSub >= 0) {
                best = Math.max(best, 12_000 - skuSub);
            }
        }

        String primary = tokens.stream()
                .max(Comparator.comparingInt(String::length))
                .orElse(tokens.get(0));
        int fuzzy = bestFuzzyDistance(primary, primaryWords, name, variant, brand);
        int maxEdits = maxEdits(primary.length());
        if (fuzzy >= 0 && fuzzy <= maxEdits) {
            best = Math.max(best, 10_000 - fuzzy * 400);
        }

        return best;
    }

    public static <T> List<T> rankAndFilter(List<T> items, java.util.function.Function<T, SearchableText> textOf, String query) {
        if (items == null || items.isEmpty() || isBlankQuery(query)) {
            return items == null ? List.of() : List.copyOf(items);
        }
        record Scored<T>(T item, int score) {
        }
        List<Scored<T>> scored = new ArrayList<>(items.size());
        for (T item : items) {
            int s = score(textOf.apply(item), query);
            if (s > 0) {
                scored.add(new Scored<>(item, s));
            }
        }
        scored.sort(Comparator
                .<Scored<T>>comparingInt(Scored::score).reversed()
                .thenComparing(s -> {
                    SearchableText t = textOf.apply(s.item());
                    return norm(t.name());
                })
                .thenComparing(s -> {
                    SearchableText t = textOf.apply(s.item());
                    return norm(t.sku());
                }));
        List<T> out = new ArrayList<>(scored.size());
        for (Scored<T> row : scored) {
            out.add(row.item());
        }
        return out;
    }

    private enum MatchMode {
        WORD_PREFIX,
        SUBSTRING
    }

    private static int aliasScore(
            List<String> tokens,
            String primaryHaystack,
            String[] primaryWords,
            String brand,
            String name
    ) {
        int best = 0;
        List<String> expanded = new ArrayList<>();
        for (String token : tokens) {
            List<String> aliases = ALIASES.get(token);
            if (aliases != null) {
                expanded.addAll(aliases);
            }
            String singular = singularize(token);
            if (!singular.equals(token)) {
                List<String> singularAliases = ALIASES.get(singular);
                if (singularAliases != null) {
                    expanded.addAll(singularAliases);
                }
            }
        }
        if (expanded.isEmpty()) {
            return 0;
        }
        for (String expansion : expanded) {
            String exp = norm(expansion);
            if (exp.isBlank()) {
                continue;
            }
            if (name.equals(exp) || brand.equals(exp)) {
                best = Math.max(best, 41_000);
            }
            if (name.startsWith(exp) || brand.startsWith(exp) || primaryHaystack.contains(exp)) {
                best = Math.max(best, 34_000);
            }
            String[] expWords = splitWords(exp);
            if (expWords.length > 0) {
                int cost = greedyTokenCost(java.util.Arrays.asList(expWords), primaryWords, MatchMode.WORD_PREFIX);
                if (cost >= 0) {
                    best = Math.max(best, 33_000 - cost);
                }
            }
        }
        return best;
    }

    private static int sizeMatchScore(List<String> tokens, Set<String> itemSizeKeys) {
        if (itemSizeKeys.isEmpty()) {
            return 0;
        }
        int matched = 0;
        for (String token : tokens) {
            String key = normalizeSizeToken(token);
            if (key != null && itemSizeKeys.contains(key)) {
                matched++;
            } else if (token.chars().allMatch(Character::isDigit) && itemSizeKeys.stream()
                    .anyMatch(k -> k.endsWith(":" + token) || k.equals("n:" + token))) {
                matched++;
            }
        }
        if (matched == 0) {
            return 0;
        }
        if (matched == tokens.size()) {
            return 32_000;
        }
        return 15_000 + matched * 500;
    }

    private static List<String> expandSignificantTokens(List<String> tokens) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String token : tokens) {
            if (token.length() >= 2) {
                out.add(token);
                String singular = singularize(token);
                if (!singular.equals(token) && singular.length() >= 2) {
                    out.add(singular);
                }
            }
        }
        return List.copyOf(out);
    }

    private static int greedyTokenCost(List<String> tokens, String[] words, MatchMode mode) {
        if (tokens.isEmpty() || words.length == 0) {
            return -1;
        }
        List<String> ordered = new ArrayList<>(tokens);
        ordered.sort(Comparator.comparingInt(String::length).reversed());
        boolean[] used = new boolean[words.length];
        int cost = 0;
        for (String token : ordered) {
            int bestIdx = -1;
            int bestCost = Integer.MAX_VALUE;
            for (int i = 0; i < words.length; i++) {
                if (used[i]) {
                    continue;
                }
                int c = tokenWordCost(token, words[i], mode);
                if (c < bestCost) {
                    bestCost = c;
                    bestIdx = i;
                }
            }
            if (bestIdx < 0 || bestCost == Integer.MAX_VALUE) {
                return -1;
            }
            used[bestIdx] = true;
            cost += bestCost;
        }
        return cost;
    }

    private static int tokenWordCost(String token, String word, MatchMode mode) {
        if (word.equals(token) || word.equals(singularize(token)) || singularize(word).equals(token)) {
            return 0;
        }
        if (word.startsWith(token) || singularize(word).startsWith(token)) {
            return 1;
        }
        if (mode == MatchMode.SUBSTRING) {
            if (token.length() == 1) {
                return Integer.MAX_VALUE;
            }
            if (word.contains(token)) {
                return 3 + word.indexOf(token);
            }
        }
        return Integer.MAX_VALUE;
    }

    private static int bestFuzzyDistance(
            String token,
            String[] words,
            String name,
            String variant,
            String brand
    ) {
        int best = Integer.MAX_VALUE;
        for (String word : words) {
            best = Math.min(best, damerauLevenshtein(token, word));
            if (word.length() >= token.length()) {
                best = Math.min(best, damerauLevenshtein(token, word.substring(0, token.length())));
            }
        }
        for (String field : List.of(name, variant, brand)) {
            if (field == null || field.isBlank()) {
                continue;
            }
            best = Math.min(best, damerauLevenshtein(token, field));
            for (String word : splitWords(field)) {
                best = Math.min(best, damerauLevenshtein(token, word));
            }
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }

    public static int maxEdits(int length) {
        if (length <= 2) {
            return 0;
        }
        if (length == 3) {
            return 1;
        }
        if (length <= 5) {
            return 2;
        }
        return Math.min(3, length / 3);
    }

    public static int damerauLevenshtein(String a, String b) {
        Objects.requireNonNull(a);
        Objects.requireNonNull(b);
        if (a.equals(b)) {
            return 0;
        }
        int n = a.length();
        int m = b.length();
        if (n == 0) {
            return m;
        }
        if (m == 0) {
            return n;
        }
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= m; j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost);
                if (i > 1 && j > 1
                        && a.charAt(i - 1) == b.charAt(j - 2)
                        && a.charAt(i - 2) == b.charAt(j - 1)) {
                    dp[i][j] = Math.min(dp[i][j], dp[i - 2][j - 2] + 1);
                }
            }
        }
        return dp[n][m];
    }

    static String normalizeSizeToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String token = raw.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        Matcher m = SIZE_TOKEN.matcher(token);
        if (!m.matches()) {
            return null;
        }
        double amount;
        try {
            amount = Double.parseDouble(m.group(1));
        } catch (NumberFormatException ex) {
            return null;
        }
        String unit = m.group(2);
        if (unit == null || unit.isBlank()) {
            return "n:" + stripTrailingZero(amount);
        }
        return switch (canonicalUnit(unit)) {
            case "ml" -> "ml:" + stripTrailingZero(amount);
            case "l" -> "ml:" + stripTrailingZero(amount * 1000d);
            case "g" -> "g:" + stripTrailingZero(amount);
            case "kg" -> "g:" + stripTrailingZero(amount * 1000d);
            case "pc" -> "pc:" + stripTrailingZero(amount);
            default -> "n:" + stripTrailingZero(amount);
        };
    }

    static Set<String> extractSizeKeys(String text) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return keys;
        }
        Matcher m = SIZE_IN_TEXT.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String raw = m.group(2) == null ? m.group(1) : m.group(1) + m.group(2);
            String key = normalizeSizeToken(raw);
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static String canonicalUnit(String unit) {
        String token = unit.toLowerCase(Locale.ROOT);
        if (token.startsWith("millilitre") || token.equals("ml")) {
            return "ml";
        }
        if (token.startsWith("litre") || token.startsWith("liter") || token.equals("l")) {
            return "l";
        }
        if (token.startsWith("kilogram") || token.equals("kg")) {
            return "kg";
        }
        if (token.startsWith("gram") || token.equals("gm") || token.equals("g")) {
            return "g";
        }
        if (token.startsWith("pc") || token.equals("pack")) {
            return "pc";
        }
        return token;
    }

    private static String stripTrailingZero(double amount) {
        if (Math.rint(amount) == amount) {
            return Long.toString(Math.round(amount));
        }
        return Double.toString(amount);
    }

    private static String singularize(String token) {
        if (token == null || token.length() < 4 || !token.endsWith("s") || token.endsWith("ss")) {
            return token == null ? "" : token;
        }
        if (token.endsWith("ies") && token.length() > 4) {
            return token.substring(0, token.length() - 3) + "y";
        }
        return token.substring(0, token.length() - 1);
    }

    private static String initialsOf(String[] words) {
        if (words == null || words.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word == null || word.isBlank()) {
                continue;
            }
            // Skip pure numeric size tokens in initials (Supa Loaf 800g → sl, not sl8).
            if (word.chars().allMatch(Character::isDigit) || normalizeSizeToken(word) != null) {
                continue;
            }
            sb.append(word.charAt(0));
        }
        return sb.toString();
    }

    private static String norm(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String joinHaystack(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(part);
        }
        return sb.toString();
    }

    private static String[] splitWords(String haystack) {
        if (haystack == null || haystack.isBlank()) {
            return new String[0];
        }
        String cleaned = NON_ALNUM.matcher(haystack).replaceAll(" ").trim();
        if (cleaned.isBlank()) {
            return new String[0];
        }
        return TOKEN_SPLIT.split(cleaned);
    }

    private static String compact(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replaceAll("[^a-z0-9]", "");
    }

    private static Map<String, List<String>> buildAliases() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        putAlias(map, "coke", "coca", "cola", "coca cola");
        putAlias(map, "coca", "coca cola", "coke");
        putAlias(map, "cc", "coca", "cola", "coca cola");
        putAlias(map, "fanta", "fanta");
        putAlias(map, "fo", "fanta", "orange");
        putAlias(map, "sl", "supa", "loaf");
        putAlias(map, "uht", "uht", "milk");
        putAlias(map, "pw", "power", "king");
        putAlias(map, "bm", "brookside", "milk");
        putAlias(map, "brook", "brookside");
        putAlias(map, "soda", "soft drink", "coke", "fanta", "sprite");
        putAlias(map, "chips", "fries", "crisps");
        putAlias(map, "fries", "chips");
        putAlias(map, "unga", "maize", "flour");
        putAlias(map, "tissue", "toilet", "paper");
        putAlias(map, "oil", "cooking oil", "salad oil");
        putAlias(map, "smokie", "smokey", "sausage");
        putAlias(map, "smokey", "smokie", "sausage");
        putAlias(map, "mayai", "egg", "eggs");
        putAlias(map, "egg", "eggs", "mayai");
        putAlias(map, "eggs", "egg", "mayai");
        putAlias(map, "kienyeji", "kienyeji");
        putAlias(map, "ketchup", "catchup", "tomato sauce");
        putAlias(map, "catchup", "ketchup", "tomato sauce");
        putAlias(map, "ketepa", "ketepa", "tea");
        putAlias(map, "delmonte", "del monte", "delmonte");
        return Map.copyOf(map);
    }

    private static void putAlias(Map<String, List<String>> map, String key, String... expansions) {
        map.put(key, List.of(expansions));
    }
}
