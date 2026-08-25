package zelisline.ub.ai.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import zelisline.ub.ai.api.dto.ProductPolishRequest;
import zelisline.ub.ai.api.dto.ProductPolishResponse;
import zelisline.ub.ai.application.provider.AiChatCompletionRequest;
import zelisline.ub.ai.application.provider.AiChatCompletionResult;
import zelisline.ub.ai.application.provider.AiProviderRouter;
import zelisline.ub.ai.domain.AiRequestLog;
import zelisline.ub.ai.repository.AiRequestLogRepository;
import zelisline.ub.catalog.domain.Category;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.domain.ItemType;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;

/**
 * AI review of a single product: goes through the product's details (name, brand,
 * size, description, department, category, pricing, stock levels) and returns
 * concrete suggestions — including a better-fitting category picked from the
 * business's real categories. Id-based suggestions are validated against the
 * tenant before they are returned, so the UI can apply them directly.
 */
@Service
@RequiredArgsConstructor
public class ProductPolishService {

    private static final Logger log = LoggerFactory.getLogger(ProductPolishService.class);
    private static final int MAX_TOKENS = 1400;
    private static final int MAX_NAME = 500;
    private static final int MAX_BRAND = 255;
    private static final int MAX_SIZE = 50;
    private static final int MAX_DESCRIPTION = 10_000;

    private final SokoMindRuntimeService runtimeService;
    private final AiProviderRouter providerRouter;
    private final AiRequestLogRepository requestLogRepository;
    private final ItemRepository itemRepository;
    private final CategoryRepository categoryRepository;
    private final ItemTypeRepository itemTypeRepository;
    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT =
            """
            You are the catalog quality assistant inside Kiosk (Palmart), a POS for Kenyan shops.
            You review exactly one product and return ONLY a JSON object — no markdown fences, no commentary before or after.

            Rules:
            - Improve only what is clearly weak; otherwise set the field to null.
            - Name: shopper-searchable, e.g. "Brookside Mala 500ml". Keep brand + product + size when available. Do not invent facts.
            - Brand / size: keep short and clean; null when already fine or unknown.
            - Description: 2–3 short sentences a shopper would read. Plain text. Never mention SKU, barcode, inventory, or warehouse language. Skip if the existing description is already good.
            - Category: pick the best-fitting category ONLY from the "Available categories" list and return its EXACT name. Never invent one. Consider what the product is, its size, and how Kenyan shops normally group it. If the current category is already the best fit, return null.
            - Department: pick ONLY from the "Available departments" list (exact label). Never invent one. null when the current one is fine.
            - Pricing: if the sell price is missing or the margin is unhealthy (below ~10% or negative), suggest a sensible sell price and/or cost price in the shop currency. Round to realistic retail numbers (e.g. nearest 5 or 10 for small items). For weighed items (sold per kg), be conservative and only suggest when clearly needed.
            - Stock levels: only suggest min stock / reorder level / reorder qty when values are missing or clearly too low for a fast-moving item. Whole numbers only.
            - "issues": 1–4 short human-readable problems, e.g. "No description", "Negative margin", "Category 'Fruit' is a poor fit for a dairy product".
            - "summary": 2–3 sentences in warm, simple Kenyan English explaining the review and the most important change.

            Return JSON with EXACTLY these keys:
            {"summary": string, "issues": [string], "name": string|null, "brand": string|null, "size": string|null,
             "description": string|null, "categoryName": string|null, "categoryReason": string|null,
             "itemTypeName": string|null, "itemTypeReason": string|null,
             "sellPrice": number|null, "costPrice": number|null, "pricingReason": string|null,
             "minStockLevel": number|null, "reorderLevel": number|null, "reorderQty": number|null,
             "stockReason": string|null}
            """;

    @Transactional
    public ProductPolishResponse polish(String businessId, String userId, ProductPolishRequest request) {
        if (!runtimeService.isGuideEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "SokoMind Guide is disabled. Enable it in Super Admin → Platform → SokoMind.");
        }
        Item item = itemRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(request.itemId().trim(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));

        List<Category> categories = categoryRepository.findByBusinessIdOrderByPositionAsc(businessId);
        List<ItemType> types = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(businessId);
        if (types.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Add departments first (Your shop → Departments).");
        }

        String requestId = UUID.randomUUID().toString();
        long started = System.currentTimeMillis();

        AiRequestLog requestLog = new AiRequestLog();
        requestLog.setId(requestId);
        requestLog.setBusinessId(businessId);
        requestLog.setUserId(userId);
        requestLog.setSkill("polish_product");
        requestLog.setSurface("products.catalog");
        requestLog.setRoutePath("/products");
        requestLog.setCreatedAt(Instant.now());

        try {
            String userPrompt = buildUserPrompt(item, categories, types);
            AiChatCompletionRequest completion = new AiChatCompletionRequest(
                    null,
                    List.of(
                            new AiChatCompletionRequest.AiChatMessage("system", SYSTEM_PROMPT),
                            new AiChatCompletionRequest.AiChatMessage("user", userPrompt)),
                    0.2,
                    MAX_TOKENS);
            AiChatCompletionResult result = providerRouter.completeSmart(completion);
            long latency = System.currentTimeMillis() - started;
            requestLog.setSuccess(true);
            requestLog.setProvider(result.provider());
            requestLog.setModel(result.model());
            requestLog.setPromptTokens(result.promptTokens());
            requestLog.setCompletionTokens(result.completionTokens());
            requestLog.setLatencyMs((int) Math.min(latency, Integer.MAX_VALUE));
            requestLogRepository.save(requestLog);

            return mapResponse(requestId, result.content(), categories, types);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            long latency = System.currentTimeMillis() - started;
            requestLog.setSuccess(false);
            requestLog.setLatencyMs((int) Math.min(latency, Integer.MAX_VALUE));
            requestLog.setErrorMessage(truncate(ex.getMessage(), 500));
            requestLogRepository.save(requestLog);
            log.warn("Product polish failed for item {}: {}", item.getId(), ex.getMessage());
            throw ex;
        }
    }

    private static String buildUserPrompt(Item item, List<Category> categories, List<ItemType> types) {
        StringBuilder sb = new StringBuilder();
        sb.append("Product to review:\n");
        sb.append("- Name: ").append(nullToDash(item.getName())).append('\n');
        sb.append("- Brand: ").append(nullToDash(item.getBrand())).append('\n');
        sb.append("- Size: ").append(nullToDash(item.getSize())).append('\n');
        sb.append("- Description: ").append(nullToDash(item.getDescription())).append('\n');
        sb.append("- Department: ")
                .append(nullToDash(typeName(types, item.getItemTypeId())))
                .append('\n');
        Category currentCategory = categoryById(categories, item.getCategoryId());
        sb.append("- Category: ")
                .append(nullToDash(currentCategory != null ? currentCategory.getName() : null));
        if (currentCategory != null && currentCategory.getParentId() != null) {
            String parent = categoryName(categories, currentCategory.getParentId());
            if (parent != null) {
                sb.append(" (under ").append(parent).append(')');
            }
        }
        if (currentCategory != null && currentCategory.getDefaultMarkupPct() != null) {
            sb.append(" (default markup ")
                    .append(currentCategory.getDefaultMarkupPct().stripTrailingZeros().toPlainString())
                    .append("%)");
        }
        sb.append('\n');
        sb.append("- Sell price: ").append(money(item.getBundlePrice())).append('\n');
        sb.append("- Cost price: ").append(money(item.getBuyingPrice())).append('\n');
        sb.append("- Margin %: ").append(marginPct(item.getBundlePrice(), item.getBuyingPrice())).append('\n');
        sb.append("- Min stock: ").append(money(item.getMinStockLevel())).append('\n');
        sb.append("- Reorder level: ").append(money(item.getReorderLevel())).append('\n');
        sb.append("- Reorder qty: ").append(money(item.getReorderQty())).append('\n');
        sb.append("- Unit: ").append(nullToDash(item.getUnitType())).append('\n');
        sb.append("- Sold by weight: ").append(item.isWeighed() ? "yes" : "no").append('\n');
        sb.append("- Active: ").append(item.isActive() ? "yes" : "no").append('\n');
        sb.append("- SKU: ").append(nullToDash(item.getSku())).append('\n');
        sb.append("- Barcode: ").append(nullToDash(item.getBarcode())).append('\n');
        sb.append('\n');
        sb.append("Available categories (exact names):\n");
        for (Category c : categories) {
            sb.append("- ").append(c.getName());
            if (c.getParentId() != null) {
                String parent = categoryName(categories, c.getParentId());
                if (parent != null) {
                    sb.append(" (under ").append(parent).append(')');
                }
            }
            sb.append('\n');
        }
        sb.append('\n');
        sb.append("Available departments (exact labels):\n");
        for (ItemType t : types) {
            sb.append("- ").append(t.getLabel()).append('\n');
        }
        return sb.toString();
    }

    private ProductPolishResponse mapResponse(
            String requestId,
            String content,
            List<Category> categories,
            List<ItemType> types
    ) {
        if (content == null || content.isBlank()) {
            return emptyResponse(requestId, "The AI returned an empty review. Please try again.");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(extractJsonObject(content));
        } catch (Exception ex) {
            log.debug("Polish response was not JSON: {}", ex.getMessage());
            return emptyResponse(requestId, "Could not read the AI review. Please try again.");
        }
        if (root == null || !root.isObject()) {
            return emptyResponse(requestId, "Could not read the AI review. Please try again.");
        }

        String summary = text(root, "summary");
        List<String> issues = stringList(root.get("issues"));

        String name = boundedText(root, "name", MAX_NAME);
        String brand = boundedText(root, "brand", MAX_BRAND);
        String size = boundedText(root, "size", MAX_SIZE);
        String description = boundedText(root, "description", MAX_DESCRIPTION);

        Category match = resolveCategory(root.get("categoryName"), categories);
        ItemType typeMatch = resolveItemType(root.get("itemTypeName"), types);

        BigDecimal sell = clampedMoney(root.get("sellPrice"));
        BigDecimal cost = clampedMoney(root.get("costPrice"));
        String pricingReason = text(root, "pricingReason");

        BigDecimal minStock = wholeNumber(root.get("minStockLevel"));
        BigDecimal reorderLevel = wholeNumber(root.get("reorderLevel"));
        BigDecimal reorderQty = wholeNumber(root.get("reorderQty"));
        String stockReason = text(root, "stockReason");

        return new ProductPolishResponse(
                requestId,
                summary,
                issues,
                name,
                brand,
                size,
                description,
                match != null ? match.getId() : null,
                match != null ? match.getName() : null,
                text(root, "categoryReason"),
                typeMatch != null ? typeMatch.getId() : null,
                typeMatch != null ? typeMatch.getLabel() : null,
                text(root, "itemTypeReason"),
                sell,
                cost,
                pricingReason,
                minStock,
                reorderLevel,
                reorderQty,
                stockReason);
    }

    private static ProductPolishResponse emptyResponse(String requestId, String summary) {
        return new ProductPolishResponse(
                requestId, summary, List.of(), null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    private static Category resolveCategory(JsonNode nameNode, List<Category> categories) {
        String name = nameNode == null ? null : nameNode.asText(null);
        if (name == null || name.isBlank()) {
            return null;
        }
        String needle = name.trim();
        Category exact = null;
        Category starts = null;
        for (Category c : categories) {
            String candidate = c.getName() == null ? "" : c.getName().trim();
            if (candidate.isEmpty()) {
                continue;
            }
            if (candidate.equalsIgnoreCase(needle)) {
                return c;
            }
            if (exact == null && candidate.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))) {
                exact = c;
            }
            if (starts == null && candidate.toLowerCase(Locale.ROOT).startsWith(needle.toLowerCase(Locale.ROOT))) {
                starts = c;
            }
        }
        return exact != null ? exact : starts;
    }

    private static ItemType resolveItemType(JsonNode nameNode, List<ItemType> types) {
        String name = nameNode == null ? null : nameNode.asText(null);
        if (name == null || name.isBlank()) {
            return null;
        }
        String needle = name.trim();
        for (ItemType t : types) {
            if (t.getLabel() != null && t.getLabel().trim().equalsIgnoreCase(needle)) {
                return t;
            }
        }
        for (ItemType t : types) {
            if (t.getLabel() != null
                    && t.getLabel().trim().toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))) {
                return t;
            }
        }
        return null;
    }

    /** Strip markdown fences and keep only the first balanced JSON object. */
    static String extractJsonObject(String content) {
        String trimmed = content.trim();
        int start = trimmed.indexOf('{');
        if (start < 0) {
            return "{}";
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
        return "{}";
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        String s = value.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    private static String boundedText(JsonNode node, String key, int max) {
        String value = text(node, key);
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static List<String> stringList(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode row : array) {
            String s = row.asText("").trim();
            if (!s.isEmpty()) {
                out.add(s.length() <= 200 ? s : s.substring(0, 200));
            }
        }
        return out.size() <= 4 ? out : out.subList(0, 4);
    }

    private static BigDecimal clampedMoney(JsonNode node) {
        BigDecimal value = number(node);
        if (value == null) {
            return null;
        }
        BigDecimal scaled = value.setScale(2, RoundingMode.HALF_UP);
        if (scaled.signum() <= 0 || scaled.compareTo(new BigDecimal("100000000")) > 0) {
            return null;
        }
        return scaled.stripTrailingZeros();
    }

    private static BigDecimal wholeNumber(JsonNode node) {
        BigDecimal value = number(node);
        if (value == null) {
            return null;
        }
        BigDecimal scaled = value.setScale(0, RoundingMode.HALF_UP);
        if (scaled.signum() <= 0 || scaled.compareTo(new BigDecimal("100000000")) > 0) {
            return null;
        }
        return scaled;
    }

    private static BigDecimal number(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        try {
            return node.decimalValue();
        } catch (Exception ex) {
            return null;
        }
    }

    private static Category categoryById(List<Category> categories, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return categories.stream().filter(c -> id.equals(c.getId())).findFirst().orElse(null);
    }

    private static String categoryName(List<Category> categories, String id) {
        Category c = categoryById(categories, id);
        return c == null ? null : c.getName();
    }

    private static String typeName(List<ItemType> types, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return types.stream()
                .filter(t -> id.equals(t.getId()))
                .map(ItemType::getLabel)
                .findFirst()
                .orElse(null);
    }

    private static String marginPct(BigDecimal sell, BigDecimal cost) {
        if (sell == null || sell.signum() <= 0 || cost == null) {
            return "—";
        }
        BigDecimal profit = sell.subtract(cost);
        return profit.multiply(new BigDecimal("100"))
                .divide(sell, 1, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private static String money(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "—" : value.trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
