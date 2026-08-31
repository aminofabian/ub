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
        String prompt = ProductDescriptionGeneratorService.buildUserPrompt(
                request, categories, departments, "");
        assertThat(prompt).contains("Name: Omo 1kg");
        assertThat(prompt).contains("Brand: Omo");
        assertThat(prompt).contains("id=d1  Grocery");
        assertThat(prompt).contains("catch-all");
        assertThat(prompt).contains("id=d2  Household");
        assertThat(prompt).contains("id=c1  Detergent");
        assertThat(prompt).doesNotContain("Current department");
        assertThat(prompt).contains("Web lookup returned nothing");
        assertThat(prompt).doesNotContain("lorem ipsum");
    }

    @Test
    void buildUserPromptIncludesWebFactsWhenPresent() {
        var request = new GenerateProductDescriptionRequest(
                "Nuvita", null, null, null, null, null, null, null, null);
        String prompt = ProductDescriptionGeneratorService.buildUserPrompt(
                request,
                java.util.List.of(),
                java.util.List.of(),
                "- Nuvita Biscuits — Kenafric cream biscuits sold in Kenya.");
        assertThat(prompt).contains("source of truth");
        assertThat(prompt).contains("cream biscuits");
        assertThat(prompt).doesNotContain("Web lookup returned nothing");
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
                depts,
                "Omo 1kg",
                "Omo");
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
                depts,
                "Omo 1kg",
                "Omo");
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
                java.util.List.of(),
                "Something",
                null);
        assertThat(out.createItemType()).isFalse();
        assertThat(out.createCategory()).isFalse();
        assertThat(out.itemTypeId()).isNull();
        assertThat(out.categoryId()).isNull();
    }

    @Test
    void parsePlainTextAsDescriptionOnly() {
        var out = ProductDescriptionGeneratorService.parseModelContent(
                MAPPER, "Fresh milk for tea.", java.util.List.of(), java.util.List.of(), "Milk", null);
        assertThat(out.description()).isEqualTo("Fresh milk for tea.");
        assertThat(out.createCategory()).isFalse();
        assertThat(out.createItemType()).isFalse();
    }

    @Test
    void nuvitaIsNotFiledAsBabyCare() {
        var baby = new ProductDescriptionGeneratorService.Named("c9", "Baby Care");
        var snacks = new ProductDescriptionGeneratorService.Named("c2", "Biscuits");
        var out = ProductDescriptionGeneratorService.parseModelContent(
                MAPPER,
                """
                {"description":"Tasty biscuits.","categoryId":"c9","categoryName":"Baby Care","departmentName":"Baby Care"}
                """,
                java.util.List.of(baby, snacks),
                java.util.List.of(),
                "Nuvita",
                "Nuvita");
        assertThat(out.categoryId()).isNull();
        assertThat(out.createCategory()).isFalse();
        assertThat(out.createItemType()).isFalse();
        assertThat(out.itemTypeName()).isNull();
    }

    @Test
    void pampersMayUseBabyCare() {
        var baby = new ProductDescriptionGeneratorService.Named("c9", "Baby Care");
        var out = ProductDescriptionGeneratorService.parseModelContent(
                MAPPER,
                """
                {"description":"Nappies.","categoryId":"c9"}
                """,
                java.util.List.of(baby),
                java.util.List.of(),
                "Pampers baby dry",
                "Pampers");
        assertThat(out.categoryId()).isEqualTo("c9");
        assertThat(out.createCategory()).isFalse();
    }
}
