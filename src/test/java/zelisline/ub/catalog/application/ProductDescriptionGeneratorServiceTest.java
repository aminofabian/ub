package zelisline.ub.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.catalog.api.dto.GenerateProductDescriptionRequest;

class ProductDescriptionGeneratorServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
                null,
                "Goods");
        var categories = java.util.List.of(new ProductDescriptionGeneratorService.Named("c1", "Dairy"));
        var departments = java.util.List.of(new ProductDescriptionGeneratorService.Named("d1", "Goods"));
        String prompt = ProductDescriptionGeneratorService.buildUserPrompt(request, categories, departments);
        assertThat(prompt).contains("Product name: Brookside 500ml");
        assertThat(prompt).contains("Current category: Dairy");
        assertThat(prompt).contains("Current department: Goods");
        assertThat(prompt).contains("Brand: Brookside");
        assertThat(prompt).contains("Size: 500ml");
        assertThat(prompt).doesNotContain("lorem ipsum");
        assertThat(prompt).contains("500ml pack");
        assertThat(prompt).contains("- Dairy");
        assertThat(prompt).contains("- Goods");
    }

    @Test
    void parsePlainTextAsDescriptionOnly() {
        var out = ProductDescriptionGeneratorService.parseModelContent(
                MAPPER, "Fresh milk for tea.", java.util.List.of(), java.util.List.of());
        assertThat(out.description()).isEqualTo("Fresh milk for tea.");
        assertThat(out.createCategory()).isFalse();
        assertThat(out.createItemType()).isFalse();
    }

    @Test
    void parseJsonSelectsExistingCategoryAndDepartment() {
        var cats = java.util.List.of(new ProductDescriptionGeneratorService.Named("c1", "Dairy"));
        var depts = java.util.List.of(new ProductDescriptionGeneratorService.Named("d1", "Goods"));
        var out = ProductDescriptionGeneratorService.parseModelContent(
                MAPPER,
                """
                {"description":"Creamy everyday milk.","categoryName":"Dairy","departmentName":"Goods"}
                """,
                cats,
                depts);
        assertThat(out.description()).isEqualTo("Creamy everyday milk.");
        assertThat(out.categoryId()).isEqualTo("c1");
        assertThat(out.createCategory()).isFalse();
        assertThat(out.itemTypeId()).isEqualTo("d1");
        assertThat(out.createItemType()).isFalse();
    }

    @Test
    void parseJsonFlagsNewCategoryAndDepartment() {
        var out = ProductDescriptionGeneratorService.parseModelContent(
                MAPPER,
                """
                {"description":"Crisp apples.","categoryName":"Fruit","departmentName":"Produce"}
                """,
                java.util.List.of(),
                java.util.List.of());
        assertThat(out.description()).isEqualTo("Crisp apples.");
        assertThat(out.categoryName()).isEqualTo("Fruit");
        assertThat(out.createCategory()).isTrue();
        assertThat(out.itemTypeName()).isEqualTo("Produce");
        assertThat(out.createItemType()).isTrue();
        assertThat(out.categoryId()).isNull();
        assertThat(out.itemTypeId()).isNull();
    }
}
