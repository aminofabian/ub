package zelisline.ub.catalog.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import zelisline.ub.catalog.api.dto.GenerateProductDescriptionRequest;
import zelisline.ub.catalog.infrastructure.DeepSeekRapidApiClient;

@Service
@RequiredArgsConstructor
public class ProductDescriptionGeneratorService {

    private final DeepSeekRapidApiClient deepSeekClient;

    public String generate(GenerateProductDescriptionRequest request) {
        return deepSeekClient.complete(buildPrompt(request));
    }

    private static String buildPrompt(GenerateProductDescriptionRequest request) {
        List<String> lines = new ArrayList<>();
        lines.add(
                "Write a concise, professional product description for a retail catalog / POS system.");
        lines.add(
                "Use 2–4 short sentences. Plain text only — no title, bullets, or markdown.");
        lines.add("Product details:");
        lines.add("- Name: " + request.name().trim());
        appendIfPresent(lines, "Category", request.categoryName());
        appendIfPresent(lines, "Brand", request.brand());
        appendIfPresent(lines, "Size / variant", request.size());
        appendIfPresent(lines, "Variant label", request.variantName());
        appendIfPresent(lines, "Unit", request.unitType());
        appendIfPresent(lines, "SKU", request.sku());
        appendIfPresent(lines, "Barcode", request.barcode());
        return String.join("\n", lines);
    }

    private static void appendIfPresent(List<String> lines, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        lines.add("- " + label + ": " + value.trim());
    }
}
