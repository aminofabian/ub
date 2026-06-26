package zelisline.ub.globalcatalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

class CatalogProductMatchNormalizerTest {

    @Test
    void compactNameIgnoresSpacingPunctuationAndUnitFormatting() {
        assertThat(CatalogProductMatchNormalizer.compact("COCACOLA 500ML"))
                .isEqualTo(CatalogProductMatchNormalizer.compact("Coca cola - 500 Ml"));
        assertThat(CatalogProductMatchNormalizer.compact("Sprite - 1.25Litres"))
                .isEqualTo(CatalogProductMatchNormalizer.compact("SPRITE 1.25L"));
    }

    @Test
    void matchKeysAlignGlobalAndTenantLabelVariants() {
        Set<String> tenantKeys = CatalogProductMatchNormalizer.matchKeys(
                "COCACOLA 500ML",
                null,
                null,
                null
        );
        Set<String> globalKeys = CatalogProductMatchNormalizer.matchKeys(
                "Coca cola - 500 Ml",
                "Coca cola",
                "500 Ml",
                null
        );

        assertThat(tenantKeys).isNotEmpty();
        assertThat(globalKeys).containsAnyElementsOf(tenantKeys);
    }

    @Test
    void brandAndSizeKeysMatchWhenNameFormattingDiffers() {
        Set<String> tenantKeys = CatalogProductMatchNormalizer.matchKeys(
                "Brookside - lala sweetened 500g",
                null,
                "lala sweetened 500g",
                "lala sweetened 500g"
        );
        Set<String> globalKeys = CatalogProductMatchNormalizer.matchKeys(
                "Brookside - lala sweetened 500g",
                "Brookside",
                "lala sweetened 500g",
                null
        );

        assertThat(tenantKeys).containsAnyElementsOf(globalKeys);
    }
}
