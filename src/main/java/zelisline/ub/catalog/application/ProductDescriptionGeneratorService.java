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

            You do not know every brand. Do not guess from the name.
            If a Web lookup block is present, that is the source of truth for what the product is
            (Nuvita biscuits stay biscuits — never Baby Care). File department and category from those facts.

            Description:
            - Use only facts in the name, brand, and size. If you are unsure what it is, say it is a shop product by name — do not claim baby, medicinal, organic, imported, or fortified.

            How to choose department and category:
            1. Search the lists. Pick the existing id that actually fits this product.
            2. Specialized aisles (Baby Care, Pharmacy, Vitamins, Clinic) need a proving word in the product name (diapers, formula, baby wipes, syrup). Otherwise do not pick or create them.
            3. Create a new name only when the product type is obvious from the name AND no listed option fits (Omo → Household / Detergent, Blue Band → Dairy / Margarine).
            4. When unsure, pick the closest general food / snacks / grocery option on the list. Do not invent a niche aisle.

            Hard rules:
            - Words in the product name win. "Kabras sugar" is sugar — not cereals. "Omo" is detergent.
            - Department and category are different levels. Do not copy the same label to both (not Cereals / Cereals). Category is the tighter shelf (Grocery + Sugar, Household + Detergent).
            - Cereals = flakes, maize, wheat, oats, Weetabix. Not sugar, salt, or tea. Salt, tea, rice, and flour belong in Grocery or their own shelf — never Cereals.
            - Only use an id that appears in the list. Never invent ids.
            - Grocery / Goods are catch-alls for detergent, milk, margarine, oil, soda, or electronics — pick or create a better aisle. Sugar, salt, tea, rice, flour, and biscuits may live in Grocery.
            - Ignore current form values; they are often the first dropdown option.
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
    private final ProductWebFactsService productWebFactsService;

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
            String webFacts;
            try {
                webFacts = productWebFactsService.lookup(request.name(), request.brand());
            } catch (Exception ex) {
                log.debug("Product web lookup skipped: {}", ex.getMessage());
                webFacts = "";
            }
            AiChatCompletionResult result = providerRouter.completeSmart(
                    new AiChatCompletionRequest(
                            null,
                            List.of(
                                    new AiChatCompletionRequest.AiChatMessage("system", SYSTEM_PROMPT),
                                    new AiChatCompletionRequest.AiChatMessage(
                                            "user",
                                            buildUserPrompt(request, categories, departments, webFacts))),
                            0.15,
                            MAX_TOKENS));
            GenerateProductDescriptionResponse parsed =
                    parseModelContent(
                            objectMapper,
                            result.content(),
                            categories,
                            departments,
                            request.name(),
                            request.brand());
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
            ObjectMapper mapper,
            String content,
            List<Named> categories,
            List<Named> departments,
            String productName,
            String brand) {
        String json = extractJsonObject(content);
        if (json != null) {
            try {
                JsonNode root = mapper.readTree(json);
                if (root != null && root.isObject()) {
                    String description = sanitize(text(root, "description"));
                    Pick category =
                            resolvePick(root, "categoryId", "categoryName", categories, productName, brand);
                    Pick department =
                            resolvePick(root, "departmentId", "departmentName", departments, productName, brand);
                    department = refineDepartment(department, departments, productName, brand, category);
                    category = refineCategory(category, categories, department, productName, brand);
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

    static Pick resolvePick(
            JsonNode root,
            String idKey,
            String nameKey,
            List<Named> options,
            String productName,
            String brand) {
        Named byId = findById(text(root, idKey), options);
        if (byId != null
                && allowed(byId.name(), productName, brand)
                && !forbiddenCatchAll(byId.name(), productName, brand)) {
            return new Pick(byId.id(), byId.name(), false);
        }
        String proposed = boundName(text(root, nameKey));
        Named byName = findByExactName(proposed, options);
        if (byName != null
                && allowed(byName.name(), productName, brand)
                && !forbiddenCatchAll(byName.name(), productName, brand)) {
            return new Pick(byName.id(), byName.name(), false);
        }
        if (proposed != null
                && !forbiddenCatchAll(proposed, productName, brand)
                && allowed(proposed, productName, brand)) {
            return new Pick(null, proposed, true);
        }
        return Pick.none();
    }

    static Pick refineDepartment(
            Pick department,
            List<Named> departments,
            String productName,
            String brand,
            Pick category) {
        if (department != null && department.name() != null) {
            // A brand-new department that would just repeat the product's shelf or the
            // category (Sugar dept + Sugar cat) is a duplicate label, not a filing.
            // Prefer an existing Grocery/Goods aisle; otherwise leave the department
            // for the user instead of inventing a redundant one.
            if (department.create()
                    && duplicates(department.name(), category, productName, brand)) {
                Named catchAll = findCatchAll(departments);
                return catchAll != null
                        ? new Pick(catchAll.id(), catchAll.name(), false)
                        : Pick.none();
            }
            return department;
        }
        if (!CatalogAiGuard.isDryGroceryStaple(productName, brand)) {
            return Pick.none();
        }
        Named catchAll = findCatchAll(departments);
        return catchAll != null
                ? new Pick(catchAll.id(), catchAll.name(), false)
                : Pick.none();
    }

    /** True when the invented department label repeats the product's shelf or the category. */
    private static boolean duplicates(
            String departmentName, Pick category, String productName, String brand) {
        String shelf = CatalogAiGuard.namedShelf(productName, brand);
        if (shelf != null && departmentName.equalsIgnoreCase(shelf)) {
            return true;
        }
        return category != null
                && category.name() != null
                && departmentName.equalsIgnoreCase(category.name());
    }

    private static Named findCatchAll(List<Named> departments) {
        if (departments == null) {
            return null;
        }
        for (Named option : departments) {
            if (isCatchAll(option.name())) {
                return option;
            }
        }
        return null;
    }

    static Pick refineCategory(
            Pick category, List<Named> categories, Pick department, String productName, String brand) {
        String shelf = CatalogAiGuard.namedShelf(productName, brand);
        boolean sameAsDepartment = category != null
                && category.name() != null
                && department != null
                && department.name() != null
                && category.name().equalsIgnoreCase(department.name());
        boolean missing = category == null || category.name() == null;
        if (shelf == null || (!missing && !sameAsDepartment)) {
            return category == null ? Pick.none() : category;
        }
        Named existing = findByExactName(shelf, categories);
        if (existing != null) {
            return new Pick(existing.id(), existing.name(), false);
        }
        return new Pick(null, shelf, true);
    }

    private static boolean forbiddenCatchAll(String aisleName, String productName, String brand) {
        if (!isCatchAll(aisleName)) {
            return false;
        }
        return !CatalogAiGuard.isDryGroceryStaple(productName, brand);
    }

    private static boolean allowed(String aisleName, String productName, String brand) {
        if (aisleName == null) {
            return false;
        }
        return CatalogAiGuard.allows(aisleName, productName, brand);
    }

    static String buildUserPrompt(
            GenerateProductDescriptionRequest request,
            List<Named> categories,
            List<Named> departments,
            String webFacts) {
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
        if (webFacts != null && !webFacts.isBlank()) {
            lines.add("Web lookup (source of truth for what this product is — do not contradict):");
            lines.add(webFacts);
        } else {
            lines.add("Web lookup returned nothing. Do not guess a specialist product type.");
        }
        lines.add("");
        lines.add("Search these departments. Return the winning id, or null + a new name if none is a real home.");
        appendNamedList(lines, departments);
        lines.add("");
        lines.add("Search these categories. Return the winning id, or null + a new name if none is a real home.");
        appendNamedList(lines, categories);
        lines.add("");
        lines.add("Words in the name win. Do not copy the same label to department and category.");
        lines.add("Sugar, salt, tea, rice, and flour are never Cereals.");
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
