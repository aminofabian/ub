package zelisline.ub.sales.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

class DrawoutApprovalTokenTest {

    private final DrawoutApprovalToken token = new DrawoutApprovalToken("test-secret");

    @Test
    void roundTrip_returnsDrawoutId() {
        String issued = token.issue("draw-1", Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(token.verifyDrawoutId(issued)).isEqualTo("draw-1");
    }

    @Test
    void expired_returnsNull() {
        String issued = token.issue("draw-1", Instant.now().minusSeconds(5));
        assertThat(token.verifyDrawoutId(issued)).isNull();
    }

    @Test
    void tampered_returnsNull() {
        String issued = token.issue("draw-1", Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(token.verifyDrawoutId(issued + "x")).isNull();
        assertThat(token.verifyDrawoutId(null)).isNull();
        assertThat(token.verifyDrawoutId("not.a.token")).isNull();
    }
}
