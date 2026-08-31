package zelisline.ub.catalog.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import zelisline.ub.ai.application.provider.AiChatCompletionRequest;
import zelisline.ub.ai.application.provider.AiChatCompletionResult;
import zelisline.ub.ai.application.provider.AiProviderRouter;
import zelisline.ub.ai.domain.AiRequestLog;
import zelisline.ub.ai.repository.AiRequestLogRepository;
import zelisline.ub.catalog.api.dto.GenerateProductDescriptionRequest;
import zelisline.ub.catalog.api.dto.GenerateProductDescriptionResponse;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;

/**
 * Product copy plus a category and department suggestion. Uses the same
 * SokoMind provider as storefront theme AI.
 */
@Service
@RequiredArgsConstructor
public class ProductDescriptionGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(ProductDescriptionGeneratorService.class);
    private static final String SKILL = "product_description";
    private static final int MAX_TOKENS = 700;
    private static final int MAX_LIST = 80;
    private static final int MAX_NAME = 80;

    private static final String SYSTEM_PROMPT =
            """
            You help a Kenyan shop owner file a product on the correct shelf. Return ONE JSON object and nothing else.

            JSON schema:
            {
              "description": "2-3 sentences, plain text, no markdown or quotes",
              "categoryName": "string",
              "departmentName": "string"
            }

            Description style:
            - Natural, warm, helpful — what a shopper would enjoy reading
            - Lead with what the product is (the real product type, not a vague grocery item)
            - Never mention SKU, barcode, product codes, inventory, or warehouse language
            - Ignore placeholder or Latin filler (lorem ipsum, culpa nihil, temporibus)

            Merchandising (Kenyan duka / supermarket):
            - Department = the aisle a shopper walks to. Category = the bay / shelf name on the till
            - Use exact names from the available lists when they are a real home for THIS product
            - Never keep Grocery, Goods, General, Other, or similar catch-alls if a more specific department exists or should be created
            - The "current" department on the form is often just the first option — ignore it unless it is already the best home
            - Invent shop-shelf names, not cooking-science names. Short Title Case (1-3 words)
            - Blue Band, Flora, Ramia = Dairy / Margarine (a spread). NOT Grocery. NOT Cooking fat
            - Kimbo, Cowboy, Kasuku = Oils & fats / Cooking fat
            - Elianto, Salit, Golden Fry = Oils & fats / Cooking oil
            - Brookside milk = Dairy / Fresh milk
            - Coca-Cola, Fanta, Sprite = Beverages / Soft drinks
            """;

    private static final Pattern PLACEHOLDER_WORD =
            Pattern.compile(
                    "\\b(lorem|ipsum|dolor|amet|consectetur|adipiscing|culpa|nihil|tempor|"
                            + "temporibus|aute|sint|elit|eiusmod|incididunt|labore|dolore|"
                            + "aliqua|veniam|quis|nostrud|exercitation)\\b",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern PACK_OR_UNIT_HINT =
            Pattern.compile(
                    "\\b(piece|pieces|pcs?|pack|box|carton|bundle|kg|g|ml|l|litre|liter|unit|units)\\b|\\d+\\s*(x|×)",
                    Pattern.CASE_INSENSITIVE);

    public record Named(String id, String name) {}

    private final AiProviderRouter providerRouter;
    private final AiRequestLogRepository requestLogRepository;
    private final CategoryRepository categoryRepository;
    private final ItemTypeRepository itemTypeRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public GenerateProductDescriptionResponse generate(
            String businessId, String userId, GenerateProductDescriptionRequest request) {
        List<Named> categories = categoryRepository.findByBusinessIdOrderByPositionAsc(businessId).stream()
                .filter(c -> c.getName() != null && !c.getName().isBlank())
                .limit(MAX_LIST)
                .map(c -> new Named(c.getId(), c.getName().trim()))
                .toList();
        List<Named> departments = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(businessId).stream()
                .filter(t -> t.getLabel() != null && !t.getLabel().isBlank())
                .limit(MAX_LIST)
                .map(t -> new Named(t.getId(), t.getLabel().trim()))
                .toList();

        String requestId = UUID.randomUUID().toString();
        long started = System.currentTimeMillis();
        AiRequestLog requestLog = new AiRequestLog();
        requestLog.setId(requestId);
        requestLog.setBusinessId(businessId);
        requestLog.setUserId(userId);
        requestLog.setSkill(SKILL);
        requestLog.setSurface("products.create");
        requestLog.setRoutePath("/products");
        requestLog.setCreatedAt(Instant.now());

        try {
            AiChatCompletionResult result = providerRouter.completeSmart(
                    new AiChatCompletionRequest(
                            null,
                            List.of(
                                    new AiChatCompletionRequest.AiChatMessage("system", SYSTEM_PROMPT),
                                    new AiChatCompletionRequest.AiChatMessage(
                                            "user", buildUserPrompt(request, categories, departments))),
                            0.2,
                            MAX_TOKENS));
            GenerateProductDescriptionResponse parsed = applyShelfHint(
                    request,
                    parseModelContent(objectMapper, result.content(), categories, departments),
                    categories,
                    departments);
            if (parsed.description() == null || parsed.description().isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "AI provider returned empty content");
            }
            long latency = System.currentTimeMillis() - started;
            requestLog.setSuccess(true);
            requestLog.setProvider(result.provider());
            requestLog.setModel(result.model());
            requestLog.setPromptTokens(result.promptTokens());
            requestLog.setCompletionTokens(result.completionTokens());
            requestLog.setLatencyMs((int) Math.min(latency, Integer.MAX_VALUE));
            requestLogRepository.save(requestLog);
            return parsed;
        } catch (RuntimeException ex) {
            long latency = System.currentTimeMillis() - started;
            requestLog.setSuccess(false);
            requestLog.setLatencyMs((int) Math.min(latency, Integer.MAX_VALUE));
            requestLog.setErrorMessage(truncate(ex.getMessage(), 500));
            requestLogRepository.save(requestLog);
            log.warn("Product description generation failed: {}", ex.getMessage());
            throw ex;
        }
    }

    static GenerateProductDescriptionResponse parseModelContent(
            ObjectMapper mapper, String content, List<Named> categories, List<Named> departments) {
        String json = extractJsonObject(content);
        if (json != null) {
            try {
                JsonNode root = mapper.readTree(json);
                if (root != null && root.isObject()) {
                    String description = sanitize(text(root, "description"));
                    Named category = matchNamed(text(root, "categoryName"), categories);
                    Named department = matchNamed(text(root, "departmentName"), departments);
                    String categoryName = boundName(text(root, "categoryName"));
                    String departmentName = boundName(text(root, "departmentName"));
                    boolean createCategory = category == null && categoryName != null;
                    boolean createItemType = department == null && departmentName != null;
                    return new GenerateProductDescriptionResponse(
                            description,
                            category != null ? category.id() : null,
                            category != null ? category.name() : categoryName,
                            createCategory,
                            department != null ? department.id() : null,
                            department != null ? department.name() : departmentName,
                            createItemType);
                }
            } catch (Exception ex) {
                log.debug("Description JSON parse failed: {}", ex.getMessage());
            }
        }
        return GenerateProductDescriptionResponse.descriptionOnly(sanitize(content));
    }

    static GenerateProductDescriptionResponse applyShelfHint(
            GenerateProductDescriptionRequest request,
            GenerateProductDescriptionResponse parsed,
            List<Named> categories,
            List<Named> departments) {
        KenyanShelfHints.ShelfHint hint = KenyanShelfHints.match(request.name(), request.brand());
        if (hint == null || parsed == null || parsed.description() == null || parsed.description().isBlank()) {
            return parsed;
        }

        Named category = keepIfSpecific(parsed.categoryId(), parsed.categoryName(), categories, hint);
        Named department = keepIfSpecific(parsed.itemTypeId(), parsed.itemTypeName(), departments, hint);
        if (category == null) {
            category = firstExisting(hint.categories(), categories);
        }
        if (department == null) {
            department = firstExisting(hint.departments(), departments);
        }

        String categoryName = category != null ? category.name() : hint.preferredCategory();
        String departmentName = department != null ? department.name() : hint.preferredDepartment();
        return new GenerateProductDescriptionResponse(
                parsed.description(),
                category != null ? category.id() : null,
                categoryName,
                category == null,
                department != null ? department.id() : null,
                departmentName,
                department == null);
    }

    private static Named keepIfSpecific(
            String id, String name, List<Named> options, KenyanShelfHints.ShelfHint hint) {
        if (id == null || id.isBlank()) {
            return null;
        }
        if (KenyanShelfHints.isCatchAll(name) || hint.avoids(name)) {
            return null;
        }
        return matchNamed(name, options);
    }

    private static Named firstExisting(List<String> preferred, List<Named> options) {
        for (String name : preferred) {
            Named hit = matchNamed(name, options);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    static String buildUserPrompt(
            GenerateProductDescriptionRequest request, List<Named> categories, List<Named> departments) {
        List<String> lines = new ArrayList<>();
        lines.add("Write a customer-facing description, then file this product on the correct Kenyan shop shelf.");
        lines.add("Product name: " + request.name().trim());
        if (KenyanShelfHints.isCatchAll(request.categoryName())) {
            lines.add("Form default category (catch-all — do not keep): " + request.categoryName().trim());
        } else {
            appendIfUseful(lines, "Current category", request.categoryName());
        }
        if (KenyanShelfHints.isCatchAll(request.itemTypeName())) {
            lines.add("Form default department (catch-all — do not keep): " + request.itemTypeName().trim());
        } else {
            appendIfUseful(lines, "Current department", request.itemTypeName());
        }
        KenyanShelfHints.ShelfHint hint = KenyanShelfHints.match(request.name(), request.brand());
        if (hint != null) {
            lines.add("Shop-floor hint: department \"" + hint.preferredDepartment()
                    + "\", category \"" + hint.preferredCategory()
                    + "\". Follow this unless an available name is a better exact match.");
        }
        appendIfUseful(lines, "Brand", request.brand());
        appendIfUseful(lines, "Size", request.size());
        appendIfUseful(lines, "Variant or option", request.variantName());
        appendIfUseful(lines, "Sold as", request.unitType());
        String packHint = packHintFromSku(request.sku());
        if (packHint != null) {
            lines.add("Pack / quantity (mention naturally if relevant): " + packHint);
        }
        lines.add("");
        lines.add("Available categories (prefer exact names):");
        if (categories.isEmpty()) {
            lines.add("- (none yet — suggest a short new category name)");
        } else {
            for (Named c : categories) {
                lines.add("- " + c.name());
            }
        }
        lines.add("");
        lines.add("Available departments (prefer exact labels):");
        if (departments.isEmpty()) {
            lines.add("- (none yet — suggest a short new department name)");
        } else {
            for (Named d : departments) {
                if (KenyanShelfHints.isCatchAll(d.name())) {
                    lines.add("- " + d.name() + " (catch-all; last resort only)");
                } else {
                    lines.add("- " + d.name());
                }
            }
        }
        lines.add("");
        lines.add("Return JSON only.");
        return String.join("\n", lines);
    }

    static Named matchNamed(String raw, List<Named> options) {
        String needle = boundName(raw);
        if (needle == null || options == null || options.isEmpty()) {
            return null;
        }
        String lower = needle.toLowerCase(Locale.ROOT);
        Named contains = null;
        Named starts = null;
        for (Named option : options) {
            String candidate = option.name() == null ? "" : option.name().trim();
            if (candidate.isEmpty()) {
                continue;
            }
            if (candidate.equalsIgnoreCase(needle)) {
                return option;
            }
            String cl = candidate.toLowerCase(Locale.ROOT);
            if (starts == null && cl.startsWith(lower)) {
                starts = option;
            }
            if (contains == null && lower.length() >= 4 && cl.contains(lower)) {
                contains = option;
            }
        }
        return starts != null ? starts : contains;
    }

    static String sanitize(String content) {
        if (content == null) {
            return "";
        }
        String s = content.strip();
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            int lastFence = s.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                s = s.substring(firstNl + 1, lastFence).strip();
            }
        }
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                s = s.substring(1, s.length() - 1).strip();
            }
        }
        return s;
    }

    static String extractJsonObject(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.trim();
        int start = trimmed.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return trimmed.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        String s = value.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    private static String boundName(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || isLikelyPlaceholder(trimmed)) {
            return null;
        }
        return trimmed.length() <= MAX_NAME ? trimmed : trimmed.substring(0, MAX_NAME);
    }

    private static void appendIfUseful(List<String> lines, String label, String value) {
        String cleaned = cleanField(value);
        if (cleaned == null) {
            return;
        }
        lines.add(label + ": " + cleaned);
    }

    private static String cleanField(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (isLikelyPlaceholder(trimmed)) {
            return null;
        }
        return trimmed;
    }

    private static String packHintFromSku(String sku) {
        String cleaned = cleanField(sku);
        if (cleaned == null) {
            return null;
        }
        if (!PACK_OR_UNIT_HINT.matcher(cleaned).find() && !cleaned.matches(".*\\d+.*")) {
            return null;
        }
        return cleaned;
    }

    private static boolean isLikelyPlaceholder(String value) {
        return PLACEHOLDER_WORD.matcher(value).find();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
