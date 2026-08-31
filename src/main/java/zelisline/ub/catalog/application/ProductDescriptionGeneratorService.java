package zelisline.ub.catalog.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

import zelisline.ub.ai.application.provider.AiChatCompletionRequest;
import zelisline.ub.ai.application.provider.AiChatCompletionResult;
import zelisline.ub.ai.application.provider.AiProviderRouter;
import zelisline.ub.ai.domain.AiRequestLog;
import zelisline.ub.ai.repository.AiRequestLogRepository;
import zelisline.ub.catalog.api.dto.GenerateProductDescriptionRequest;

/**
 * Short product-copy generation. Uses the same SokoMind provider as storefront
 * theme AI (Super Admin → Platform → SokoMind), not the legacy RapidAPI catalog key.
 */
@Service
@RequiredArgsConstructor
public class ProductDescriptionGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(ProductDescriptionGeneratorService.class);
    private static final String SKILL = "product_description";
    private static final int MAX_TOKENS = 400;

    private static final String SYSTEM_PROMPT =
            """
            You write short product descriptions for a retail shop catalog (in-store shelves, POS, and online storefront).

            Style:
            - 2–3 sentences, plain text only (no title, bullets, markdown, or quotes)
            - Natural, warm, and helpful—like copy a shopper would enjoy reading
            - Lead with what the product is and why someone would buy it; mention benefits and everyday use
            - Keep it concise; avoid filler, clichés, and stiff corporate phrases

            Hard rules:
            - NEVER mention SKU, barcode, product codes, "scannable", inventory, or warehouse language
            - Do NOT list every attribute in one sentence; weave only useful details in naturally
            - If brand, variant, or size text looks like placeholder or nonsense (e.g. Latin filler like lorem ipsum, \
            "culpa nihil", "temporibus"), ignore it completely—do not quote or paraphrase it
            - If the product name already includes brand or variant, do not repeat awkwardly
            - Pack size (e.g. "100 pieces") may be mentioned naturally as quantity, never as a code
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

    private final AiProviderRouter providerRouter;
    private final AiRequestLogRepository requestLogRepository;

    @Transactional
    public String generate(String businessId, String userId, GenerateProductDescriptionRequest request) {
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
            AiChatCompletionResult result = providerRouter.completeMini(
                    new AiChatCompletionRequest(
                            null,
                            List.of(
                                    new AiChatCompletionRequest.AiChatMessage("system", SYSTEM_PROMPT),
                                    new AiChatCompletionRequest.AiChatMessage("user", buildUserPrompt(request))),
                            0.5,
                            MAX_TOKENS));
            String description = sanitize(result.content());
            if (description.isBlank()) {
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
            return description;
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

    static String buildUserPrompt(GenerateProductDescriptionRequest request) {
        List<String> lines = new ArrayList<>();
        lines.add("Write a customer-facing description using only the facts below.");
        lines.add("Product name: " + request.name().trim());
        appendIfUseful(lines, "Category", request.categoryName());
        appendIfUseful(lines, "Brand", request.brand());
        appendIfUseful(lines, "Size", request.size());
        appendIfUseful(lines, "Variant or option", request.variantName());
        appendIfUseful(lines, "Sold as", request.unitType());
        String packHint = packHintFromSku(request.sku());
        if (packHint != null) {
            lines.add("Pack / quantity (mention naturally if relevant): " + packHint);
        }
        lines.add("");
        lines.add("Return only the description text.");
        return String.join("\n", lines);
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
