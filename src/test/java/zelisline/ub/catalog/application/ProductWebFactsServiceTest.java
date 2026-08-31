package zelisline.ub.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class ProductWebFactsServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void buildQueryAddsKenyaAndSkipsDuplicateBrand() {
        assertThat(ProductWebFactsService.buildQuery("Nuvita", "Nuvita")).isEqualTo("Nuvita Kenya");
        assertThat(ProductWebFactsService.buildQuery("Blue Band 500g", "Blue Band"))
                .isEqualTo("Blue Band 500g Kenya");
    }

    @Test
    void parseDuckDuckGoHtmlExtractsTitleAndSnippet() {
        String html =
                """
                <a rel="nofollow" class="result__a" href="/x">Nuvita Biscuits</a>
                <a class="result__snippet" href="/x">Kenafric Nuvita cream biscuits sold in Kenyan shops.</a>
                """;
        var lines = ProductWebFactsService.parseDuckDuckGoHtml(html);
        assertThat(lines).isNotEmpty();
        assertThat(lines.get(0)).contains("Nuvita Biscuits");
        assertThat(lines.get(0)).contains("cream biscuits");
    }

    @Test
    void parseDuckDuckGoJsonUsesAbstract() throws Exception {
        var root = MAPPER.readTree(
                """
                {"Heading":"Nuvita","AbstractText":"A Kenyan biscuit brand by Kenafric."}
                """);
        var lines = ProductWebFactsService.parseDuckDuckGoJson(root);
        assertThat(lines.get(0)).contains("biscuit");
    }

    @Test
    void wikipediaIgnoresUnrelatedTitles() throws Exception {
        var root = MAPPER.readTree(
                """
                ["Nuvita",["Vitamin A","Nuvita"],["A nutrient.","Kenyan biscuit brand."],["u1","u2"]]
                """);
        var lines = ProductWebFactsService.parseWikipediaOpenSearch(root, "Nuvita");
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("biscuit");
    }

    @Test
    void relevantRequiresAProductToken() {
        assertThat(ProductWebFactsService.relevant("Nuvita cream biscuits", "Nuvita")).isTrue();
        assertThat(ProductWebFactsService.relevant("Baby vitamin drops", "Nuvita")).isFalse();
    }
}
