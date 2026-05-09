package zelisline.ub.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SkuGenerationServiceTest {

    @Test
    void abbreviateCategory_knownValues() {
        assertThat(SkuGenerationService.abbreviateCategory("Fresh Milk")).isEqualTo("FML");
        assertThat(SkuGenerationService.abbreviateCategory("Long Life Milk")).isEqualTo("UHT");
        assertThat(SkuGenerationService.abbreviateCategory("Body Lotion")).isEqualTo("LOT");
        assertThat(SkuGenerationService.abbreviateCategory("Bar Soap")).isEqualTo("BSO");
        assertThat(SkuGenerationService.abbreviateCategory("Washing Powder")).isEqualTo("WPO");
        assertThat(SkuGenerationService.abbreviateCategory("Margarine")).isEqualTo("MAR");
        assertThat(SkuGenerationService.abbreviateCategory("Tomato Sauce")).isEqualTo("TSA");
        assertThat(SkuGenerationService.abbreviateCategory("Biscuits")).isEqualTo("BIS");
    }

    @Test
    void abbreviateCategory_fallbackForUnknown() {
        assertThat(SkuGenerationService.abbreviateCategory("Energy Drink")).isEqualTo("EDR");
        assertThat(SkuGenerationService.abbreviateCategory("Shampoo")).isEqualTo("SHA");
    }

    @Test
    void abbreviateBrand_knownValues() {
        assertThat(SkuGenerationService.abbreviateBrand("Brookside")).isEqualTo("BRK");
        assertThat(SkuGenerationService.abbreviateBrand("KCC")).isEqualTo("KCC");
        assertThat(SkuGenerationService.abbreviateBrand("Fresha")).isEqualTo("FSH");
        assertThat(SkuGenerationService.abbreviateBrand("Amara")).isEqualTo("AMA");
        assertThat(SkuGenerationService.abbreviateBrand("Omo")).isEqualTo("OMO");
        assertThat(SkuGenerationService.abbreviateBrand("Blue Band")).isEqualTo("BLB");
        assertThat(SkuGenerationService.abbreviateBrand("Dairyland")).isEqualTo("DLL");
    }

    @Test
    void abbreviateBrand_fallbackForUnknown() {
        assertThat(SkuGenerationService.abbreviateBrand("Nescafe")).isEqualTo("NES");
        assertThat(SkuGenerationService.abbreviateBrand("CocaCola")).isEqualTo("COC");
    }

    @Test
    void abbreviateVariant_knownValues() {
        assertThat(SkuGenerationService.abbreviateVariant("Full Cream")).isEqualTo("FC");
        assertThat(SkuGenerationService.abbreviateVariant("Low Fat")).isEqualTo("LF");
        assertThat(SkuGenerationService.abbreviateVariant("Long Life")).isEqualTo("UHT");
        assertThat(SkuGenerationService.abbreviateVariant("Chocolate")).isEqualTo("CHO");
        assertThat(SkuGenerationService.abbreviateVariant("Vanilla")).isEqualTo("VAN");
    }

    @Test
    void normalizeSize_commonPatterns() {
        assertThat(SkuGenerationService.normalizeSize("250ml")).isEqualTo("250");
        assertThat(SkuGenerationService.normalizeSize("500ml")).isEqualTo("500");
        assertThat(SkuGenerationService.normalizeSize("1L")).isEqualTo("1L");
        assertThat(SkuGenerationService.normalizeSize("2L")).isEqualTo("2L");
        assertThat(SkuGenerationService.normalizeSize("250g")).isEqualTo("250");
        assertThat(SkuGenerationService.normalizeSize("1kg")).isEqualTo("1KG");
        assertThat(SkuGenerationService.normalizeSize("500 ml")).isEqualTo("500");
        assertThat(SkuGenerationService.normalizeSize("1.5L")).isEqualTo("1.5L");
    }

    @Test
    void normalizeSize_invalidReturnsNull() {
        assertThat(SkuGenerationService.normalizeSize("")).isNull();
        assertThat(SkuGenerationService.normalizeSize(null)).isNull();
        assertThat(SkuGenerationService.normalizeSize("large")).isNull();
    }
}
