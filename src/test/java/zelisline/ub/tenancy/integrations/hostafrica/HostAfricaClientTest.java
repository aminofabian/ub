package zelisline.ub.tenancy.integrations.hostafrica;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HostAfricaClientTest {

    @Test
    void parsePriceToCents_handlesCurrencyNoise() {
        assertThat(HostAfricaClient.parsePriceToCents("KES 1,250.00")).isEqualTo(125000L);
        assertThat(HostAfricaClient.parsePriceToCents("1500")).isEqualTo(150000L);
        assertThat(HostAfricaClient.parsePriceToCents(null)).isNull();
    }
}
