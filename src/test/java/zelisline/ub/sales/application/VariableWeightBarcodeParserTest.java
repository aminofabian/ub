package zelisline.ub.sales.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class VariableWeightBarcodeParserTest {

    @Test
    void parsesWeightEmbeddedLabel() {
        // PLU 01234, 347 grams -> 0.347 kg (filler digit at index 11)
        String barcode = "2012340034704";
        var config = VariableWeightBarcodeConfig.standardEnabled();

        var result = VariableWeightBarcodeParser.parse(barcode, config);

        assertThat(result).isPresent();
        assertThat(result.get().pluCode()).isEqualTo("01234");
        assertThat(result.get().embeddedWeightKg()).isEqualByComparingTo(new BigDecimal("0.347"));
        assertThat(result.get().embeddedField())
                .isEqualTo(VariableWeightBarcodeConfig.EmbeddedField.WEIGHT);
    }

    @Test
    void rejectsInvalidCheckDigit() {
        String barcode = "2012340034700";
        var config = VariableWeightBarcodeConfig.standardEnabled();

        assertThat(VariableWeightBarcodeParser.parse(barcode, config)).isEmpty();
    }

    @Test
    void rejectsWhenDisabled() {
        String barcode = "2012340034704";
        assertThat(VariableWeightBarcodeParser.parse(barcode, VariableWeightBarcodeConfig.disabled()))
                .isEmpty();
    }

    @Test
    void parsesPriceEmbeddedLabel() {
        var config = new VariableWeightBarcodeConfig(
                true,
                '2',
                1,
                5,
                6,
                5,
                VariableWeightBarcodeConfig.EmbeddedField.PRICE,
                VariableWeightBarcodeConfig.WeightUnit.GRAMS,
                true
        );
        // PLU 01234, embedded price 123.45 -> value field 12345
        String barcode = "2012341234509";
        var result = VariableWeightBarcodeParser.parse(barcode, config);
        assertThat(result).isPresent();
        assertThat(result.get().embeddedPrice()).isEqualByComparingTo(new BigDecimal("123.45"));
    }
}
