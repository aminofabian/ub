package zelisline.ub.storefront.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.catalog.domain.Item;

class StorefrontOnlinePurchaseRulesTest {

    @Test
    void weighedItem_isInStoreOnly() {
        Item item = new Item();
        item.setWeighed(true);
        assertThat(StorefrontOnlinePurchaseRules.resolveMode(item))
                .isEqualTo(StorefrontOnlinePurchaseRules.IN_STORE_ONLY);
    }

    @Test
    void pieceItem_isWebCart() {
        Item item = new Item();
        item.setWeighed(false);
        assertThat(StorefrontOnlinePurchaseRules.resolveMode(item))
                .isEqualTo(StorefrontOnlinePurchaseRules.WEB_CART);
    }

    @Test
    void wholeUnitQuantity_rejectsDecimals() {
        assertThatThrownBy(() ->
                StorefrontOnlinePurchaseRules.requireWholeUnitQuantity(new BigDecimal("1.5")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("whole units");
    }
}
