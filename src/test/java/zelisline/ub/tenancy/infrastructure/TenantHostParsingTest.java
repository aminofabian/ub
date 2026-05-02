package zelisline.ub.tenancy.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TenantHostParsingTest {

    @Test
    void stripsPortAndLowercases() {
        assertThat(TenantHostParsing.hostnameOnly("Pal.Localhost:3000")).isEqualTo("pal.localhost");
    }

    @Test
    void ipv6BracketHost() {
        assertThat(TenantHostParsing.hostnameOnly("[::1]")).isEqualTo("::1");
    }
}
