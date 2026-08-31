package zelisline.ub.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import zelisline.ub.catalog.api.dto.GenerateProductDescriptionRequest;

class ProductDescriptionGeneratorServiceTest {

    @Test
    void sanitizeStripsWrappingQuotesAndFences() {
        assertThat(ProductDescriptionGeneratorService.sanitize("\"Fresh milk for tea.\""))
                .isEqualTo("Fresh milk for tea.");
        assertThat(ProductDescriptionGeneratorService.sanitize("```\nCreamy everyday milk.\n```"))
                .isEqualTo("Creamy everyday milk.");
        assertThat(ProductDescriptionGeneratorService.sanitize(null)).isEmpty();
    }

    @Test
    void buildUserPromptIncludesUsefulFactsAndDropsPlaceholder() {
        var request = new GenerateProductDescriptionRequest(
                "Brookside 500ml",
                "Dairy",
                "Brookside",
                "500ml",
                "bottle",
                "lorem ipsum",
                "500ml pack",
                null);
        String prompt = ProductDescriptionGeneratorService.buildUserPrompt(request);
        assertThat(prompt).contains("Product name: Brookside 500ml");
        assertThat(prompt).contains("Category: Dairy");
        assertThat(prompt).contains("Brand: Brookside");
        assertThat(prompt).contains("Size: 500ml");
        assertThat(prompt).doesNotContain("lorem ipsum");
        assertThat(prompt).contains("500ml pack");
    }
}
