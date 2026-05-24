package zelisline.ub.catalog.application;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import zelisline.ub.catalog.api.dto.GenerateProductDescriptionRequest;
import zelisline.ub.catalog.infrastructure.DeepSeekRapidApiClient;

@Service
@RequiredArgsConstructor
public class ProductDescriptionGeneratorService {

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

    private final DeepSeekRapidApiClient deepSeekClient;

    public String generate(GenerateProductDescriptionRequest request) {
        return deepSeekClient.complete(SYSTEM_PROMPT, buildUserPrompt(request));
    }

    private static String buildUserPrompt(GenerateProductDescriptionRequest request) {
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
}
