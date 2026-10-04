package zelisline.ub.platform.media;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class R2PropertiesTest {

    @Test
    void derivesEndpointFromAccountId() {
        R2Properties p = new R2Properties();
        p.setAccountId(" acct123 ");
        assertThat(p.resolvedEndpoint()).isEqualTo("https://acct123.r2.cloudflarestorage.com");
    }

    @Test
    void explicitEndpointWinsAndTrailingSlashesAreTrimmed() {
        R2Properties p = new R2Properties();
        p.setAccountId("acct123");
        p.setEndpoint("https://custom.example/");
        p.setPublicBaseUrl("https://media.example.com//");
        assertThat(p.resolvedEndpoint()).isEqualTo("https://custom.example");
        assertThat(p.resolvedPublicBaseUrl()).isEqualTo("https://media.example.com");
    }

    @Test
    void listsMissingSettingsByEnvName() {
        R2Properties p = new R2Properties();
        p.setBucket("picshare-media");
        assertThat(p.missingSettings()).containsExactly(
                "R2_ACCOUNT_ID or R2_ENDPOINT", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY", "R2_PUBLIC_BASE_URL");
    }
}
