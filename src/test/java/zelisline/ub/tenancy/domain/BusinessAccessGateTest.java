package zelisline.ub.tenancy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import zelisline.ub.billing.domain.SubscriptionBillingStatus;
import zelisline.ub.billing.domain.SuspensionReason;

class BusinessAccessGateTest {

    @Test
    void inactiveActiveFlagLocksTenantRegardlessOfBilling() {
        Business business = new Business();
        business.setActive(false);
        business.setSubscriptionBillingStatus(SubscriptionBillingStatus.ACTIVE);

        business.syncAccessGate();

        assertThat(business.getTenantStatus()).isEqualTo(TenantStatus.INACTIVE);
        assertThat(business.getSuspensionReason()).isEqualTo(SuspensionReason.MANUAL_SUPPORT);
    }

    @Test
    void activeFlagWithBillingSuspendedYieldsSuspendedGate() {
        Business business = new Business();
        business.setActive(true);
        business.setSubscriptionBillingStatus(SubscriptionBillingStatus.SUSPENDED);

        business.syncAccessGate();

        assertThat(business.getTenantStatus()).isEqualTo(TenantStatus.SUSPENDED);
        assertThat(business.getSuspensionReason()).isEqualTo(SuspensionReason.BILLING_UNPAID);
    }

    @Test
    void reactivatingSaFlagWhileBillingSuspendedKeepsSuspendedGate() {
        Business business = new Business();
        business.setActive(false);
        business.setSubscriptionBillingStatus(SubscriptionBillingStatus.SUSPENDED);
        business.syncAccessGate();
        assertThat(business.getTenantStatus()).isEqualTo(TenantStatus.INACTIVE);

        business.setActive(true);
        business.syncAccessGate();

        assertThat(business.getTenantStatus()).isEqualTo(TenantStatus.SUSPENDED);
        assertThat(business.getSuspensionReason()).isEqualTo(SuspensionReason.BILLING_UNPAID);
    }

    @Test
    void activeAndPaidYieldsActiveGate() {
        Business business = new Business();
        business.setActive(true);
        business.setSubscriptionBillingStatus(SubscriptionBillingStatus.GRACE);
        business.setSuspensionReason(SuspensionReason.MANUAL_SUPPORT);

        business.syncAccessGate();

        assertThat(business.getTenantStatus()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(business.getSuspensionReason()).isNull();
    }
}
