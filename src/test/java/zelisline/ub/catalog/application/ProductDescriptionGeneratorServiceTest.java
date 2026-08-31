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
    void buildUserPromptListsIdsAndDoesNotTreatFormDefaultAsChosen() {
        var request = new GenerateProductDescriptionRequest(
                "Omo 1kg",
                "Grocery",
                "Omo",
                "1kg",
                null,
                null,
                null,
                null,
                "Grocery");
        var categories = java.util.List.of(new ProductDescriptionGeneratorService.Named("c1", "Detergent"));
        var departments = java.util.List.of(
                new ProductDescriptionGeneratorService.Named("d1", "Grocery"),
                new ProductDescriptionGeneratorService.Named("d2", "Household"));
        String prompt = ProductDescriptionGeneratorService.buildUserPrompt(request, categories, departments);
        assertThat(prompt).contains("Name: Omo 1kg");
        assertThat(prompt).contains("Brand: Omo");
        assertThat(prompt).contains("id=d1  Grocery");
        assertThat(prompt).contains("catch-all");
        assertThat(prompt).contains("id=d2  Household");
        assertThat(prompt).contains("id=c1  Detergent");
        assertThat(prompt).doesNotContain("Current department");
        assertThat(prompt).doesNotContain("lorem ipsum");
    }

    @Test
    void parsePicksExistingIdsFromTheList() {
        var cats = java.util.List.of(new ProductDescriptionGeneratorService.Named("c1", "Detergent"));
        var depts = java.util.List.of(
                new ProductDescriptionGeneratorService.Named("d1", "Grocery"),
                new ProductDescriptionGeneratorService.Named("d2", "Household"));
        var out = ProductDescriptionGeneratorService.parseModelContent(
                MAPPER,
                """
                {"description":"Laundry powder.","categoryId":"c1","departmentId":"d2"}
                """,
                cats,
                depts);
        assertThat(out.description()).isEqualTo("Laundry powder.");
        assertThat(out.categoryId()).isEqualTo("c1");
        assertThat(out.itemTypeId()).isEqualTo("d2");
        assertThat(out.createCategory()).isFalse();
        assertThat(out.createItemType()).isFalse();
    }

    @Test
    void parseRejectsCatchAllAndCreatesSuggestedDepartment() {
        var depts = java.util.List.of(new ProductDescriptionGeneratorService.Named("d1", "Grocery"));
        var out = ProductDescriptionGeneratorService.parseModelContent(
                MAPPER,
                """
                {"description":"Laundry powder.","departmentId":"d1","departmentName":"Household","categoryName":"Detergent"}
                """,
                java.util.List.of(),
                depts);
        assertThat(out.itemTypeId()).isNull();
        assertThat(out.itemTypeName()).isEqualTo("Household");
        assertThat(out.createItemType()).isTrue();
        assertThat(out.categoryName()).isEqualTo("Detergent");
        assertThat(out.createCategory()).isTrue();
    }

    @Test
    void parseDoesNotCreateCatchAllNames() {
        var out = ProductDescriptionGeneratorService.parseModelContent(
                MAPPER,
                """
                {"description":"Something.","departmentName":"Grocery","categoryName":"Goods"}
                """,
                java.util.List.of(),
                java.util.List.of());
        assertThat(out.createItemType()).isFalse();
        assertThat(out.createCategory()).isFalse();
        assertThat(out.itemTypeId()).isNull();
        assertThat(out.categoryId()).isNull();
    }

    @Test
    void parsePlainTextAsDescriptionOnly() {
        var out = ProductDescriptionGeneratorService.parseModelContent(
                MAPPER, "Fresh milk for tea.", java.util.List.of(), java.util.List.of());
        assertThat(out.description()).isEqualTo("Fresh milk for tea.");
        assertThat(out.createCategory()).isFalse();
        assertThat(out.createItemType()).isFalse();
    }
}
