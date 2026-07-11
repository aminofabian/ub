package zelisline.ub.catalog.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Tokenizes catalog search queries and ranks items by relevance so partial
 * names, abbreviations, and light typos still surface the closest products.
 *
 * <p>Matching priority (highest first):
 * <ol>
 *   <li>Exact / phrase match</li>
 *   <li>All tokens as word prefixes (greedy, one word per token)</li>
 *   <li>All tokens as substrings</li>
 *   <li>Significant tokens only (length ≥ 2) — keeps results while typing</li>
 *   <li>Fuzzy / edit-distance on the primary token</li>
 * </ol>
 */
public final class CatalogSearchSupport {

    public static final int CANDIDATE_FETCH_SIZE = 500;
    private static final int MIN_FUZZY_PREFIX_LEN = 3;
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[\\s/_\\-]+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

    private CatalogSearchSupport() {
    }

    public record SearchableText(
            String name,
            String variantName,
            String sku,
            String barcode,
            String description
    ) {
        public static SearchableText of(
                String name,
                String variantName,
                String sku,
                String barcode,
                String description
        ) {
            return new SearchableText(name, variantName, sku, barcode, description);
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
     * Longest token used for the DB {@code LIKE} candidate fetch.
     * Multi-word queries like {@code "supa s"} become {@code "supa"} so variants
     * are still retrieved before in-memory ranking.
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
     * Progressively shorter prefixes used when the candidate token returns no rows
     * (typos such as {@code suap} → try {@code sua}, then {@code su}).
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
        String phrase = String.join(" ", tokens);
        String primaryHaystack = joinHaystack(name, variant);
        String haystack = joinHaystack(name, variant, sku, barcode, description);
        String[] primaryWords = splitWords(primaryHaystack);
        String[] words = splitWords(haystack);
        String compactPhrase = compact(phrase);

        int best = 0;

        if (!name.isBlank() && name.equals(phrase)) {
            best = Math.max(best, 50_000);
        }
        if (!name.isBlank() && name.startsWith(phrase)) {
            best = Math.max(best, 45_000);
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

        // Prefer name/variant words so short tokens like "s"/"m" hit Small/Medium,
        // not incidental SKU/barcode characters.
        int prefixCost = greedyTokenCost(tokens, primaryWords, MatchMode.WORD_PREFIX);
        if (prefixCost >= 0) {
            best = Math.max(best, 30_000 - prefixCost);
        }

        int substringCost = greedyTokenCost(tokens, primaryWords, MatchMode.SUBSTRING);
        if (substringCost >= 0) {
            best = Math.max(best, 22_000 - substringCost);
        }

        List<String> significant = tokens.stream().filter(t -> t.length() >= 2).toList();
        if (!significant.isEmpty() && significant.size() < tokens.size()) {
            int sigPrefix = greedyTokenCost(significant, primaryWords, MatchMode.WORD_PREFIX);
            if (sigPrefix >= 0) {
                best = Math.max(best, 16_000 - sigPrefix);
            }
            int sigSub = greedyTokenCost(significant, primaryWords, MatchMode.SUBSTRING);
            if (sigSub >= 0) {
                best = Math.max(best, 14_000 - sigSub);
            }
        } else if (prefixCost < 0 && substringCost < 0) {
            // Longer single-token queries may match SKU/barcode words.
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
        int fuzzy = bestFuzzyDistance(primary, primaryWords, name, variant);
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

    /**
     * Greedy assignment of tokens (longest first) to distinct words.
     * Returns a small cost (lower is better) or {@code -1} when unmatched.
     */
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
        if (word.equals(token)) {
            return 0;
        }
        if (word.startsWith(token)) {
            return 1;
        }
        if (mode == MatchMode.SUBSTRING) {
            // Single-character substring matches are too noisy; require word prefix.
            if (token.length() == 1) {
                return Integer.MAX_VALUE;
            }
            if (word.contains(token)) {
                return 3 + word.indexOf(token);
            }
        }
        return Integer.MAX_VALUE;
    }

    private static int bestFuzzyDistance(String token, String[] words, String name, String variant) {
        int best = Integer.MAX_VALUE;
        for (String word : words) {
            best = Math.min(best, damerauLevenshtein(token, word));
            if (word.length() >= token.length()) {
                best = Math.min(best, damerauLevenshtein(token, word.substring(0, token.length())));
            }
        }
        if (!name.isBlank()) {
            best = Math.min(best, damerauLevenshtein(token, name));
            String[] nameWords = splitWords(name);
            for (String word : nameWords) {
                best = Math.min(best, damerauLevenshtein(token, word));
            }
        }
        if (!variant.isBlank()) {
            best = Math.min(best, damerauLevenshtein(token, variant));
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

    /**
     * Optimal string alignment distance (Damerau–Levenshtein with adjacent
     * transpositions), enough for common POS typos like {@code suap} → {@code supa}.
     */
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
        return NON_ALNUM.matcher(value).replaceAll("");
    }
}
