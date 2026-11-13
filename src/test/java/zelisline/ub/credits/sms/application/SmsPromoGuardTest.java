package zelisline.ub.credits.sms.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.sms.domain.SmsMarketingStatus;
import zelisline.ub.messaging.application.SmsMessageLogService;

@ExtendWith(MockitoExtension.class)
class SmsPromoGuardTest {

    private static final String BIZ = "biz-1";

    @Mock
    private SmsMessageLogService messageLog;

    @InjectMocks
    private SmsPromoGuard guard;

    private static Customer customer(String status) {
        Customer c = new Customer();
        c.setId("c1");
        c.setBusinessId(BIZ);
        c.setName("Wanjiku");
        c.setSmsMarketingStatus(status);
        return c;
    }

    private static CustomerPhone phone(String number, String mask) {
        CustomerPhone p = new CustomerPhone();
        p.setCustomerId("c1");
        p.setBusinessId(BIZ);
        p.setPhone(number);
        p.setMaskedMsisdn(mask);
        p.setPrimary(true);
        return p;
    }

    @Test
    void customerWhoNeverAnsweredGetsNoOffers() {
        SmsPromoGuard.Decision d = SmsPromoGuard.eligibility(
                customer(SmsMarketingStatus.UNKNOWN), phone("0722111000", null));
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo(SmsPromoGuard.SKIP_NO_CONSENT);
    }

    @Test
    void optedOutCustomerIsRefused() {
        SmsPromoGuard.Decision d = SmsPromoGuard.eligibility(
                customer(SmsMarketingStatus.OPTED_OUT), phone("0722111000", null));
        assertThat(d.reason()).isEqualTo(SmsPromoGuard.SKIP_OPTED_OUT);
    }

    @Test
    void maskedMpesaNumberCannotBeTexted() {
        SmsPromoGuard.Decision d = SmsPromoGuard.eligibility(
                customer(SmsMarketingStatus.OPTED_IN), phone(null, "2547XXXXX123"));
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo(SmsPromoGuard.SKIP_MASKED);
    }

    @Test
    void customerWithoutAPhoneIsRefused() {
        SmsPromoGuard.Decision d = SmsPromoGuard.eligibility(customer(SmsMarketingStatus.OPTED_IN), null);
        assertThat(d.reason()).isEqualTo(SmsPromoGuard.SKIP_NO_NUMBER);
    }

    @Test
    void anonymisedCustomerIsRefused() {
        Customer c = customer(SmsMarketingStatus.OPTED_IN);
        c.setAnonymisedAt(Instant.now());
        assertThat(SmsPromoGuard.eligibility(c, phone("0722111000", null)).allowed()).isFalse();
    }

    @Test
    void optedInCustomerIsAllowedWithInternationalNumber() {
        SmsPromoGuard.Decision d = SmsPromoGuard.eligibility(
                customer(SmsMarketingStatus.OPTED_IN), phone("0722111000", null));
        assertThat(d.allowed()).isTrue();
        assertThat(d.phoneDigits()).isEqualTo("254722111000");
    }

    @Test
    void weeklyLimitBlocksASecondOffer() {
        when(messageLog.promotionalSince(eq(BIZ), eq("254722111000"), any(Instant.class))).thenReturn(1L);

        SmsPromoGuard.Decision d = guard.check(
                BIZ, customer(SmsMarketingStatus.OPTED_IN), phone("0722111000", null), 1, Instant.now());

        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo(SmsPromoGuard.SKIP_WEEKLY_CAP);
    }

    @Test
    void underTheWeeklyLimitIsAllowed() {
        when(messageLog.promotionalSince(eq(BIZ), eq("254722111000"), any(Instant.class))).thenReturn(1L);

        SmsPromoGuard.Decision d = guard.check(
                BIZ, customer(SmsMarketingStatus.OPTED_IN), phone("0722111000", null), 2, Instant.now());

        assertThat(d.allowed()).isTrue();
    }
}
