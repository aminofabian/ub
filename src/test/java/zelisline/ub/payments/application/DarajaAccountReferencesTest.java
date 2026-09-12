package zelisline.ub.payments.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DarajaAccountReferencesTest {

    @Test
    void forWebOrder_usesCanonicalEightCharCode() {
        String orderId = "550e8400-e29b-41d4-a716-446655440000";
        assertThat(DarajaAccountReferences.forWebOrder(orderId)).isEqualTo("55440000");
    }

    @Test
    void forGroceryBarcode_stripsPunctuationAndFitsTwelve() {
        assertThat(DarajaAccountReferences.forGroceryBarcode("GI-ABCDEF1234"))
                .isEqualTo("GIABCDEF1234");
        assertThat(DarajaAccountReferences.forGroceryBarcode("GI-ABCDEF1234").length())
                .isLessThanOrEqualTo(12);
    }

    @Test
    void groceryBarcodeMatches_handlesTruncationAndHyphen() {
        assertThat(DarajaAccountReferences.groceryBarcodeMatches("GIABCDEF1234", "GI-ABCDEF1234")).isTrue();
        assertThat(DarajaAccountReferences.groceryBarcodeMatches("GIABCDEF12", "GI-ABCDEF1234")).isTrue();
        assertThat(DarajaAccountReferences.groceryBarcodeMatches("OTHER", "GI-ABCDEF1234")).isFalse();
    }
}
