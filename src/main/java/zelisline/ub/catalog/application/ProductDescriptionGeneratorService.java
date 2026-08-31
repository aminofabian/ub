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
 * Product copy plus a department and category pick from the shop's real lists.
 * If nothing on a list is a real home, the model proposes a name to create.
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
            You file one product into a Kenyan shop catalog. Return ONE JSON object, nothing else.

            JSON schema:
            {
              "description": "2-3 sentences, plain text, no markdown or quotes",
              "departmentId": "id from the department list, or null",
              "departmentName": "new department to create, or null",
              "categoryId": "id from the category list, or null",
              "categoryName": "new category to create, or null"
            }

            How to choose:
            1. Decide what the product actually is (detergent, margarine, milk, cooking oil, soda, rice…).
            2. Search the department list. Pick the id whose label is the aisle a shopper would walk to.
            3. Search the category list. Pick the id whose name is the shelf / bay for that product.
            4. If nothing on the list is a real home, set that id to null and propose a short Title Case name to create (1-3 words).

            Hard rules:
            - Only use an id that appears in the list. Never invent ids.
            - Grocery, Goods, General, Other, Miscellaneous are catch-alls — not a home for detergent, margarine, milk, oil, soda, or electronics. Do not pick them. Propose a better department instead (Household, Dairy, Oils & fats, Beverages, Electronics…).
            - Ignore "current" form values; they are often just the first dropdown option.
            - Omo, Ariel, Sunlight (laundry) → Household / Detergent — never Grocery.
            - Blue Band, Flora → Dairy / Margarine — never Grocery, never Cooking fat.
            - Kimbo, Cowboy → Oils & fats / Cooking fat.
            - Coca-Cola, Fanta → Beverages / Soft drinks.
            - Description: what it is and why someone would buy it. No SKU, barcode, or warehouse language.
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
                            0.15,
                            MAX_TOKENS));
            GenerateProductDescriptionResponse parsed =
                    parseModelContent(objectMapper, result.content(), categories, departments);
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
                    Pick category = resolvePick(root, "categoryId", "categoryName", categories);
                    Pick department = resolvePick(root, "departmentId", "departmentName", departments);
                    return new GenerateProductDescriptionResponse(
                            description,
                            category.id(),
                            category.name(),
                            category.create(),
                            department.id(),
                            department.name(),
                            department.create());
                }
            } catch (Exception ex) {
                log.debug("Description JSON parse failed: {}", ex.getMessage());
            }
        }
        return GenerateProductDescriptionResponse.descriptionOnly(sanitize(content));
    }

    record Pick(String id, String name, boolean create) {
        static Pick none() {
            return new Pick(null, null, false);
        }
    }

    static Pick resolvePick(JsonNode root, String idKey, String nameKey, List<Named> options) {
        Named byId = findById(text(root, idKey), options);
        if (byId != null && !isCatchAll(byId.name())) {
            return new Pick(byId.id(), byId.name(), false);
        }
        String proposed = boundName(text(root, nameKey));
        Named byName = findByExactName(proposed, options);
        if (byName != null && !isCatchAll(byName.name())) {
            return new Pick(byName.id(), byName.name(), false);
        }
        if (proposed != null && !isCatchAll(proposed)) {
            return new Pick(null, proposed, true);
        }
        return Pick.none();
    }

    static String buildUserPrompt(
            GenerateProductDescriptionRequest request, List<Named> categories, List<Named> departments) {
        List<String> lines = new ArrayList<>();
        lines.add("Product to file:");
        lines.add("Name: " + request.name().trim());
        appendIfUseful(lines, "Brand", request.brand());
        appendIfUseful(lines, "Size", request.size());
        appendIfUseful(lines, "Variant or option", request.variantName());
        appendIfUseful(lines, "Sold as", request.unitType());
        String packHint = packHintFromSku(request.sku());
        if (packHint != null) {
            lines.add("Pack / quantity (mention naturally if relevant): " + packHint);
        }
        lines.add("");
        lines.add("Search these departments. Return the winning id, or null + a new name if none is a real home.");
        appendNamedList(lines, departments);
        lines.add("");
        lines.add("Search these categories. Return the winning id, or null + a new name if none is a real home.");
        appendNamedList(lines, categories);
        lines.add("");
        lines.add("Return JSON only.");
        return String.join("\n", lines);
    }

    private static void appendNamedList(List<String> lines, List<Named> options) {
        if (options.isEmpty()) {
            lines.add("- (none yet — propose a short name to create)");
            return;
        }
        for (Named option : options) {
            if (isCatchAll(option.name())) {
                lines.add("- id=" + option.id() + "  " + option.name()
                        + "  [catch-all — do not pick; propose a better name instead]");
            } else {
                lines.add("- id=" + option.id() + "  " + option.name());
            }
        }
    }

    static Named findById(String id, List<Named> options) {
        if (id == null || options == null) {
            return null;
        }
        for (Named option : options) {
            if (id.equals(option.id())) {
                return option;
            }
        }
        return null;
    }

    static Named findByExactName(String raw, List<Named> options) {
        if (raw == null || options == null) {
            return null;
        }
        for (Named option : options) {
            if (option.name() != null && option.name().equalsIgnoreCase(raw)) {
                return option;
            }
        }
        return null;
    }

    static boolean isCatchAll(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String lower = name.trim().toLowerCase(Locale.ROOT);
        return lower.equals("grocery")
                || lower.equals("goods")
                || lower.equals("general")
                || lower.equals("general shop")
                || lower.equals("retail")
                || lower.equals("retail shop")
                || lower.equals("other")
                || lower.equals("misc")
                || lower.equals("miscellaneous")
                || lower.equals("default")
                || lower.equals("uncategorized")
                || lower.equals("uncategorised");
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
                } else if (ch == '"') {
                    inString = false;
                } else if (ch == '\\') {
                    escaped = true;
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
        if (value == null || value.isNull()) {
            return null;
        }
        String s = value.asText("").trim();
        return s.isEmpty() || s.equalsIgnoreCase("null") ? null : s;
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
