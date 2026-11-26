package zelisline.ub.suppliers.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class SupplierItemTypeFilterTest {

    @Test
    void absentMeansNoFilter() {
        SupplierItemTypeFilter.Decision d = SupplierItemTypeFilter.of(null);
        assertEquals(SupplierItemTypeFilter.Mode.NONE, d.mode());
        assertTrue(d.itemTypeIds().isEmpty());
    }

    @Test
    void emptyListMeansNoSuppliers() {
        assertEquals(
                SupplierItemTypeFilter.Mode.EMPTY,
                SupplierItemTypeFilter.of(List.of()).mode());
        assertEquals(
                SupplierItemTypeFilter.Mode.EMPTY,
                SupplierItemTypeFilter.of(List.of("  ", "")).mode());
    }

    @Test
    void nonEmptyDedupesAndTrims() {
        java.util.ArrayList<String> raw = new java.util.ArrayList<>();
        raw.add(" dept-a ");
        raw.add("dept-b");
        raw.add("dept-a");
        raw.add("");
        raw.add(null);
        SupplierItemTypeFilter.Decision d = SupplierItemTypeFilter.of(raw);
        assertEquals(SupplierItemTypeFilter.Mode.IDS, d.mode());
        assertEquals(List.of("dept-a", "dept-b"), d.itemTypeIds());
    }
}
