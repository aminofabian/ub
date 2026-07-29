package zelisline.ub.storefront.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.catalog.domain.Item;

class StorefrontOnlinePurchaseRulesTest {

    @Test
    void weighedItem_isWebCartEligible() {
        Item item = new Item();
        item.setWeighed(true);
        assertThat(StorefrontOnlinePurchaseRules.resolveMode(item))
                .isEqualTo(StorefrontOnlinePurchaseRules.WEB_CART);
        assertThat(StorefrontOnlinePurchaseRules.isWebCartEligible(item)).isTrue();
        assertThat(StorefrontOnlinePurchaseRules.allowsFractionalQuantity(item)).isTrue();
    }

    @Test
    void pieceItem_isWebCartWholeUnits() {
        Item item = new Item();
        item.setWeighed(false);
        assertThat(StorefrontOnlinePurchaseRules.resolveMode(item))
                .isEqualTo(StorefrontOnlinePurchaseRules.WEB_CART);
        assertThat(StorefrontOnlinePurchaseRules.allowsFractionalQuantity(item)).isFalse();
    }

    @Test
    void wholeUnitQuantity_rejectsDecimals() {
        assertThatThrownBy(() ->
                StorefrontOnlinePurchaseRules.requireWholeUnitQuantity(new BigDecimal("1.5")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("whole units");
    }

    @Test
    void weighedQuantity_allowsUpToThreeDecimals() {
        Item weighed = new Item();
        weighed.setWeighed(true);
        assertThatCode(() ->
                StorefrontOnlinePurchaseRules.requireValidQuantity(weighed, new BigDecimal("1.250")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() ->
                StorefrontOnlinePurchaseRules.requireValidQuantity(weighed, new BigDecimal("1.2501")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("decimal");
    }

    @Test
    void pieceQuantity_rejectsFractions() {
        Item piece = new Item();
        piece.setWeighed(false);
        assertThatThrownBy(() ->
                StorefrontOnlinePurchaseRules.requireValidQuantity(piece, new BigDecimal("1.5")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("whole units");
    }
}
