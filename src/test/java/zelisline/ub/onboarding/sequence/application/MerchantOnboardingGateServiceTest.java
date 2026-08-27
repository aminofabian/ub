package zelisline.ub.onboarding.sequence.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class MerchantOnboardingGateServiceTest {

    @Test
    void m1DueAtDefersLateEveningToNextMorning() {
        var gates = new MerchantOnboardingGateService(null, null, null, null, null, null, null, null);
        ZoneId nairobi = ZoneId.of("Africa/Nairobi");
        // 2026-08-27 20:00 Nairobi → +4h = 00:00 next day → quiet → 09:00
        Instant enrolled = Instant.parse("2026-08-27T17:00:00Z"); // 20:00 EAT
        Instant due = gates.m1DueAt(enrolled, nairobi);
        assertThat(due.atZone(nairobi).getHour()).isEqualTo(9);
        assertThat(due.atZone(nairobi).toLocalDate().toString()).isEqualTo("2026-08-28");
    }

    @Test
    void m1DueAtKeepsAfternoonSlot() {
        var gates = new MerchantOnboardingGateService(null, null, null, null, null, null, null, null);
        ZoneId nairobi = ZoneId.of("Africa/Nairobi");
        Instant enrolled = Instant.parse("2026-08-27T07:00:00Z"); // 10:00 EAT
        Instant due = gates.m1DueAt(enrolled, nairobi);
        // +4h = 14:00 EAT same day
        assertThat(due.atZone(nairobi).getHour()).isEqualTo(14);
        assertThat(due.atZone(nairobi).toLocalDate().toString()).isEqualTo("2026-08-27");
    }

    @Test
    void stemStripsSizeTokens() {
        assertThat(MerchantOnboardingGateService.stem("Coca-Cola 500ml"))
                .isEqualTo("coca-cola");
        assertThat(MerchantOnboardingGateService.stem("Coca-Cola 1L"))
                .isEqualTo("coca-cola");
    }
}
