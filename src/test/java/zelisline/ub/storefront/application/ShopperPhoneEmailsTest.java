package zelisline.ub.storefront.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShopperPhoneEmailsTest {

    @Test
    void forLocal07_normalizesCommonKenyanForms() {
        assertEquals("shopper.0714282874@phone.invalid", ShopperPhoneEmails.forLocal07("0714282874"));
        assertEquals("shopper.0714282874@phone.invalid", ShopperPhoneEmails.forLocal07("+254 714 282 874"));
        assertEquals("shopper.0714282874@phone.invalid", ShopperPhoneEmails.forLocal07("254714282874"));
    }

    @Test
    void isSynthetic_onlyMatchesPlaceholderInbox() {
        assertTrue(ShopperPhoneEmails.isSynthetic("shopper.0714282874@phone.invalid"));
        assertTrue(ShopperPhoneEmails.isSynthetic("SHOPPER.0714282874@PHONE.INVALID"));
        assertFalse(ShopperPhoneEmails.isSynthetic("chege@palmart.co.ke"));
        assertFalse(ShopperPhoneEmails.isSynthetic(null));
    }
}
