package zelisline.ub.suppliers.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SupplierSlugTest {

    @Test
    void slugifiesDisplayNames() {
        assertEquals("jamro", SupplierSlug.slugify("Jamro"));
        assertEquals("jamro-ltd", SupplierSlug.slugify("Jamro Ltd"));
        assertEquals("acme-and-sons", SupplierSlug.slugify("Acme & Sons"));
    }

    @Test
    void matchesNameOrCode() {
        assertTrue(SupplierSlug.matches("id-1", "Jamro Fresh", "JF", "jamro-fresh"));
        assertTrue(SupplierSlug.matches("id-1", "Jamro Fresh", "JF", "jf"));
        assertFalse(SupplierSlug.matches("id-1", "Jamro Fresh", "JF", "other"));
    }
}
