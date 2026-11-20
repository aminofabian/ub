package zelisline.ub.pharmacy.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ItemMedicineDetailsTest {

    @Test
    void refundedMedicineIsQuarantinedByDefault() {
        ItemMedicineDetails d = new ItemMedicineDetails();
        assertTrue(d.quarantineOnRefund());
        d.setDispensingClass(DispensingClass.OTC);
        assertTrue(d.quarantineOnRefund());
    }

    @Test
    void onlyAnOtcMedicineWithTheOverrideIsRestocked() {
        ItemMedicineDetails d = new ItemMedicineDetails();
        d.setDispensingClass(DispensingClass.OTC);
        d.setRestockOnRefund(true);
        assertFalse(d.quarantineOnRefund());

        d.setDispensingClass(DispensingClass.PRESCRIPTION_ONLY);
        assertTrue(d.quarantineOnRefund());
        d.setDispensingClass(DispensingClass.UNCLASSIFIED);
        assertTrue(d.quarantineOnRefund());
    }
}
