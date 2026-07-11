package zelisline.ub.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class CatalogSearchSupportTest {

    private static CatalogSearchSupport.SearchableText item(String name, String variant) {
        return CatalogSearchSupport.SearchableText.of(name, variant, "SKU", null, null);
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
}
