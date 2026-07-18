package zelisline.ub.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class CatalogSearchSupportTest {

    private static CatalogSearchSupport.SearchableText item(String name, String variant) {
        return CatalogSearchSupport.SearchableText.of(name, variant, "SKU", null, null);
    }

    private static CatalogSearchSupport.SearchableText item(
            String name,
            String variant,
            String brand,
            String size
    ) {
        return CatalogSearchSupport.SearchableText.of(name, variant, "SKU", null, null, brand, size);
    }

    @Test
    void candidateToken_usesLongestToken() {
        assertThat(CatalogSearchSupport.candidateToken("supa s")).isEqualTo("supa");
        assertThat(CatalogSearchSupport.candidateToken("sup l")).isEqualTo("sup");
        assertThat(CatalogSearchSupport.candidateToken("supa")).isEqualTo("supa");
    }

    @Test
    void score_prefersSmallForSupaS() {
        int small = CatalogSearchSupport.score(item("Supa Loaf Small", "Small"), "supa s");
        int medium = CatalogSearchSupport.score(item("Supa Loaf Medium", "Medium"), "supa s");
        int plain = CatalogSearchSupport.score(item("Supa Loaf", null), "supa s");
        assertThat(small).isGreaterThan(0);
        assertThat(small).isGreaterThan(medium);
        assertThat(small).isGreaterThan(plain);
        assertThat(medium).isGreaterThan(0);
        assertThat(plain).isGreaterThan(0);
    }

    @Test
    void score_prefersMediumForSupaM() {
        int medium = CatalogSearchSupport.score(item("Supa Loaf Medium", "Medium"), "supa m");
        int small = CatalogSearchSupport.score(item("Supa Loaf Small", "Small"), "supa m");
        assertThat(medium).isGreaterThan(small);
        assertThat(medium).isGreaterThan(0);
        assertThat(small).isGreaterThan(0);
    }

    @Test
    void score_matchesSupLToSupaLoaf() {
        int loaf = CatalogSearchSupport.score(item("Supa Loaf", null), "sup l");
        assertThat(loaf).isGreaterThan(0);
    }

    @Test
    void score_toleratesTypos() {
        assertThat(CatalogSearchSupport.score(item("Supa Loaf", null), "suap")).isGreaterThan(0);
        assertThat(CatalogSearchSupport.score(item("Supa Loaf", null), "supaa")).isGreaterThan(0);
        assertThat(CatalogSearchSupport.score(item("Supa Loaf Small", "Small"), "supa l")).isGreaterThan(0);
    }

    @Test
    void rankAndFilter_ordersByRelevance() {
        var small = item("Supa Loaf Small", "Small");
        var medium = item("Supa Loaf Medium", "Medium");
        var plain = item("Supa Loaf", null);
        var other = item("Bread Rolls", null);

        List<CatalogSearchSupport.SearchableText> ranked = CatalogSearchSupport.rankAndFilter(
                List.of(other, medium, plain, small),
                t -> t,
                "supa s");

        assertThat(ranked).hasSize(3);
        assertThat(ranked.get(0)).isEqualTo(small);
        assertThat(ranked).contains(medium, plain);
        assertThat(ranked).doesNotContain(other);
    }

    @Test
    void fuzzyCandidateTokens_shortensForTypos() {
        assertThat(CatalogSearchSupport.fuzzyCandidateTokens("suap"))
                .containsExactly("sua", "su");
        assertThat(CatalogSearchSupport.fuzzyCandidateTokens("supaa"))
                .contains("sup", "su");
    }

    @Test
    void damerauLevenshtein_countsTranspositionAsOne() {
        assertThat(CatalogSearchSupport.damerauLevenshtein("suap", "supa")).isEqualTo(1);
        assertThat(CatalogSearchSupport.damerauLevenshtein("supaa", "supa")).isEqualTo(1);
    }

    @Test
    void unrelatedQuery_scoresZero() {
        assertThat(CatalogSearchSupport.score(item("Supa Loaf", null), "xyzzy")).isZero();
    }

    @Test
    void score_matchesInitials() {
        assertThat(CatalogSearchSupport.score(item("Supa Loaf", null), "sl")).isGreaterThan(0);
        assertThat(CatalogSearchSupport.score(item("Fanta Orange", null), "fo")).isGreaterThan(0);
        assertThat(CatalogSearchSupport.score(item("Coca Cola", null), "cc")).isGreaterThan(0);
    }

    @Test
    void score_matchesAliases() {
        assertThat(CatalogSearchSupport.score(item("Coca Cola 500ml", null), "coke")).isGreaterThan(0);
        assertThat(CatalogSearchSupport.score(item("UHT Milk 1L", null, "Brookside", "1L"), "uht"))
                .isGreaterThan(0);
        assertThat(CatalogSearchSupport.score(item("Maize Flour 2kg", null), "unga")).isGreaterThan(0);
        assertThat(CatalogSearchSupport.score(item("Tomato Sauce", null), "catchup")).isGreaterThan(0);
    }

    @Test
    void score_matchesBrandPrefix() {
        int brook = CatalogSearchSupport.score(
                item("Full Cream Milk", null, "Brookside", "500ml"), "brook");
        assertThat(brook).isGreaterThan(0);
    }

    @Test
    void score_normalizesSizes() {
        var coke500 = item("Coca Cola", "500ml", "Coca Cola", "500ml");
        assertThat(CatalogSearchSupport.score(coke500, "500")).isGreaterThan(0);
        assertThat(CatalogSearchSupport.score(coke500, "500ml")).isGreaterThan(0);
        assertThat(CatalogSearchSupport.normalizeSizeToken("500ml"))
                .isEqualTo(CatalogSearchSupport.normalizeSizeToken("0.5l"));
        assertThat(CatalogSearchSupport.normalizeSizeToken("1kg"))
                .isEqualTo(CatalogSearchSupport.normalizeSizeToken("1000g"));
    }

    @Test
    void score_ignoresWordOrder() {
        int a = CatalogSearchSupport.score(item("Fanta Orange", null), "orange fanta");
        int b = CatalogSearchSupport.score(item("Fanta Orange", null), "fanta orange");
        assertThat(a).isGreaterThan(0);
        assertThat(b).isGreaterThan(0);
    }

    @Test
    void score_singularPlural() {
        assertThat(CatalogSearchSupport.score(item("Egg Tray", null), "eggs")).isGreaterThan(0);
        assertThat(CatalogSearchSupport.score(item("Eggs", null), "egg")).isGreaterThan(0);
    }

    @Test
    void dbCandidateTokens_includesAliasExpansions() {
        assertThat(CatalogSearchSupport.dbCandidateTokens("coke"))
                .contains("coke", "coca", "cola");
        assertThat(CatalogSearchSupport.dbCandidateTokens("sl"))
                .contains("sl", "supa", "loaf");
    }

    @Test
    void score_matchesCompactNameWithoutHyphenOrSpace() {
        assertThat(CatalogSearchSupport.score(item("Coca-Cola", null), "cocacola"))
                .isGreaterThan(0);
        assertThat(CatalogSearchSupport.score(item("Coca Cola", null), "cocacola"))
                .isGreaterThan(0);
        assertThat(CatalogSearchSupport.rankAndFilter(
                List.of(item("Bread Rolls", null), item("Coca-Cola 500ml", "500ml")),
                t -> t,
                "cocacola"))
                .extracting(CatalogSearchSupport.SearchableText::name)
                .containsExactly("Coca-Cola 500ml");
    }
}
