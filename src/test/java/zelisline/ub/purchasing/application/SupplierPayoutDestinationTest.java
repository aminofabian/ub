package zelisline.ub.purchasing.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierPayoutTypes;

class SupplierPayoutDestinationTest {

    @Test
    void mobileWalletRequiresPhone() {
        Supplier s = new Supplier();
        s.setPayoutType(SupplierPayoutTypes.MOBILE_WALLET);
        assertFalse(SupplierDisbursementService.hasAutomatedPayoutDestination(s));
        s.setPayoutPhone("254710514157");
        assertTrue(SupplierDisbursementService.hasAutomatedPayoutDestination(s));
    }

    @Test
    void tillRequiresTillNumber() {
        Supplier s = new Supplier();
        s.setPayoutType(SupplierPayoutTypes.TILL);
        assertFalse(SupplierDisbursementService.hasAutomatedPayoutDestination(s));
        s.setPayoutTillNumber("567890");
        assertTrue(SupplierDisbursementService.hasAutomatedPayoutDestination(s));
    }

    @Test
    void paybillRequiresNumberAndAccount() {
        Supplier s = new Supplier();
        s.setPayoutType(SupplierPayoutTypes.PAYBILL);
        s.setPayoutPaybillNumber("247247");
        assertFalse(SupplierDisbursementService.hasAutomatedPayoutDestination(s));
        s.setPayoutPaybillAccount("ACC-1");
        assertTrue(SupplierDisbursementService.hasAutomatedPayoutDestination(s));
    }

    @Test
    void manualIsNotAutomated() {
        Supplier s = new Supplier();
        s.setPayoutType(SupplierPayoutTypes.MANUAL);
        s.setPayoutPhone("254710514157");
        assertFalse(SupplierDisbursementService.hasAutomatedPayoutDestination(s));
    }

    @Test
    void payoutTypeHelpers() {
        assertTrue(SupplierPayoutTypes.isAutomated("till"));
        assertTrue(SupplierPayoutTypes.isAutomated("paybill"));
        assertTrue(SupplierPayoutTypes.isValid("mobile_wallet"));
        assertFalse(SupplierPayoutTypes.isAutomated("manual"));
        assertFalse(SupplierPayoutTypes.isValid("bank"));
    }
}
