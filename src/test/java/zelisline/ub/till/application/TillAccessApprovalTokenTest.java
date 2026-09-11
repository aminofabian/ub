package zelisline.ub.till.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

class TillAccessApprovalTokenTest {

    private final TillAccessApprovalToken token = new TillAccessApprovalToken("test-secret");

    @Test
    void roundTrip_returnsRequestId() {
        String issued = token.issue("req-1", Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(token.verifyRequestId(issued)).isEqualTo("req-1");
    }

    @Test
    void expired_returnsNull() {
        String issued = token.issue("req-1", Instant.now().minusSeconds(5));
        assertThat(token.verifyRequestId(issued)).isNull();
    }

    @Test
    void tampered_returnsNull() {
        String issued = token.issue("req-1", Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(token.verifyRequestId(issued + "x")).isNull();
        assertThat(token.verifyRequestId(null)).isNull();
        assertThat(token.verifyRequestId("not.a.token")).isNull();
    }
}
