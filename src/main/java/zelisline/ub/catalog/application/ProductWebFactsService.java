package zelisline.ub.catalog.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import lombok.RequiredArgsConstructor;

/**
 * Looks up what a shop product actually is so Generate with AI does not guess
 * from the brand name (Nuvita → biscuits, not baby care).
 */
@Service
@RequiredArgsConstructor
public class ProductWebFactsService {

    private static final Logger log = LoggerFactory.getLogger(ProductWebFactsService.class);
    private static final String USER_AGENT = "PalmartCatalog/1.0 (product-facts)";
    private static final int CONNECT_MS = 2000;
    private static final int SOCKET_MS = 2500;
    private static final int MAX_LINES = 5;
    private static final int MAX_LINE = 180;
    private static final int MAX_BLOCK = 1200;

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern RESULT_TITLE =
            Pattern.compile(
                    "<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*>(.*?)</a>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern RESULT_SNIPPET =
            Pattern.compile(
                    "<(?:a|td)[^>]*class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</(?:a|td)>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final ObjectMapper objectMapper;

    public String lookup(String name, String brand) {
        String query = buildQuery(name, brand);
        if (query.isBlank()) {
            return "";
        }
        Set<String> lines = new LinkedHashSet<>();
        try {
            addDuckDuckGoJson(query, lines);
        } catch (Exception ex) {
            log.debug("DuckDuckGo JSON lookup failed: {}", ex.getMessage());
        }
        try {
            addDuckDuckGoHtml(query, lines);
        } catch (Exception ex) {
            log.debug("DuckDuckGo HTML lookup failed: {}", ex.getMessage());
        }
        try {
            addWikipedia(name, lines);
        } catch (Exception ex) {
            log.debug("Wikipedia lookup failed: {}", ex.getMessage());
        }
        return format(lines);
    }

    static String buildQuery(String name, String brand) {
        String n = name == null ? "" : name.trim();
        String b = brand == null ? "" : brand.trim();
        if (n.isBlank() && b.isBlank()) {
            return "";
        }
        StringBuilder q = new StringBuilder();
        if (!n.isBlank()) {
            q.append(n);
        }
        if (!b.isBlank() && !n.toLowerCase(Locale.ROOT).contains(b.toLowerCase(Locale.ROOT))) {
            if (q.length() > 0) {
                q.append(' ');
            }
            q.append(b);
        }
        String lower = q.toString().toLowerCase(Locale.ROOT);
        if (!lower.contains("kenya") && !lower.contains("nairobi")) {
            q.append(" Kenya");
        }
        return q.toString();
    }

    static List<String> parseDuckDuckGoHtml(String html) {
        if (html == null || html.isBlank()) {
            return List.of();
        }
        List<String> titles = captures(RESULT_TITLE, html);
        List<String> snippets = captures(RESULT_SNIPPET, html);
        List<String> out = new ArrayList<>();
        int n = Math.max(titles.size(), snippets.size());
        for (int i = 0; i < n && out.size() < MAX_LINES; i++) {
            String title = i < titles.size() ? titles.get(i) : "";
            String snippet = i < snippets.size() ? snippets.get(i) : "";
            String line = join(title, snippet);
            if (!line.isBlank()) {
                out.add(clip(line, MAX_LINE));
            }
        }
        return out;
    }

    static List<String> parseDuckDuckGoJson(JsonNode root) {
        if (root == null || !root.isObject()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        String heading = text(root, "Heading");
        String abs = text(root, "AbstractText");
        if (abs == null) {
            abs = text(root, "Abstract");
        }
        String combined = join(heading, abs);
        if (!combined.isBlank()) {
            out.add(clip(combined, MAX_LINE));
        }
        JsonNode related = root.path("RelatedTopics");
        if (related.isArray()) {
            for (JsonNode topic : related) {
                if (out.size() >= MAX_LINES) {
                    break;
                }
                String t = text(topic, "Text");
                if (t != null) {
                    out.add(clip(t, MAX_LINE));
                }
            }
        }
        return out;
    }

    static List<String> parseWikipediaOpenSearch(JsonNode root, String productName) {
        if (root == null || !root.isArray() || root.size() < 3) {
            return List.of();
        }
        JsonNode titles = root.get(1);
        JsonNode descs = root.get(2);
        if (!titles.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < titles.size() && out.size() < 2; i++) {
            String title = titles.get(i).asText("");
            String desc = descs.isArray() && i < descs.size() ? descs.get(i).asText("") : "";
            if (!relevant(title + " " + desc, productName)) {
                continue;
            }
            String line = join(title, desc);
            if (!line.isBlank()) {
                out.add(clip(line, MAX_LINE));
            }
        }
        return out;
    }

    static boolean relevant(String haystack, String productName) {
        if (haystack == null || productName == null) {
            return false;
        }
        String h = haystack.toLowerCase(Locale.ROOT);
        for (String token : productName.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.length() >= 4 && h.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private void addDuckDuckGoJson(String query, Set<String> lines) throws Exception {
        String url = "https://api.duckduckgo.com/?q="
                + encode(query)
                + "&format=json&no_html=1&skip_disambig=1";
        String body = get(url, "application/json");
        if (body == null || body.isBlank()) {
            return;
        }
        lines.addAll(parseDuckDuckGoJson(objectMapper.readTree(body)));
    }

    private void addDuckDuckGoHtml(String query, Set<String> lines) {
        if (lines.size() >= MAX_LINES) {
            return;
        }
        String url = "https://html.duckduckgo.com/html/?q=" + encode(query);
        String body = get(url, "text/html");
        lines.addAll(parseDuckDuckGoHtml(body));
    }

    private void addWikipedia(String name, Set<String> lines) throws Exception {
        if (name == null || name.isBlank() || lines.size() >= MAX_LINES) {
            return;
        }
        String url = "https://en.wikipedia.org/w/api.php?action=opensearch&limit=3&namespace=0&format=json&search="
                + encode(name.trim());
        String body = get(url, "application/json");
        if (body == null || body.isBlank()) {
            return;
        }
        lines.addAll(parseWikipediaOpenSearch(objectMapper.readTree(body), name));
    }

    private static String get(String url, String accept) {
        HttpResponse<String> response = Unirest.get(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", accept)
                .connectTimeout(CONNECT_MS)
                .socketTimeout(SOCKET_MS)
                .asString();
        if (response.getStatus() < 200 || response.getStatus() >= 300) {
            return "";
        }
        return response.getBody();
    }

    private static String format(Set<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String line : lines) {
            if (n >= MAX_LINES) {
                break;
            }
            if (sb.length() + line.length() > MAX_BLOCK) {
                break;
            }
            sb.append("- ").append(line).append('\n');
            n++;
        }
        return sb.toString().strip();
    }

    private static List<String> captures(Pattern pattern, String html) {
        List<String> out = new ArrayList<>();
        Matcher m = pattern.matcher(html);
        while (m.find() && out.size() < MAX_LINES) {
            String text = stripHtml(m.group(1));
            if (!text.isBlank()) {
                out.add(text);
            }
        }
        return out;
    }

    static String stripHtml(String raw) {
        if (raw == null) {
            return "";
        }
        String s = HTML_TAG.matcher(raw).replaceAll(" ");
        s = s.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String join(String a, String b) {
        String left = a == null ? "" : a.trim();
        String right = b == null ? "" : b.trim();
        if (left.isBlank()) {
            return right;
        }
        if (right.isBlank() || right.equalsIgnoreCase(left) || left.contains(right)) {
            return left;
        }
        if (right.contains(left)) {
            return right;
        }
        return left + " — " + right;
    }

    private static String clip(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max - 1).strip() + "…";
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        String s = value.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
