package zelisline.ub.sales.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class WholesaleShapedMathTest {

    @Test
    void needsTwoTillsAndFloor() {
        assertThat(WholesaleShapedMath.matches(new BigDecimal("2500"), 2, new BigDecimal("400")))
                .isTrue();
        assertThat(WholesaleShapedMath.matches(new BigDecimal("2500"), 1, new BigDecimal("400")))
                .isFalse();
        assertThat(WholesaleShapedMath.matches(new BigDecimal("1500"), 4, new BigDecimal("200")))
                .isFalse();
    }

    @Test
    void fiveTimesMedianBeatsTheFloor() {
        assertThat(WholesaleShapedMath.matches(new BigDecimal("6000"), 2, new BigDecimal("1000")))
                .isTrue();
        assertThat(WholesaleShapedMath.matches(new BigDecimal("4000"), 2, new BigDecimal("1000")))
                .isFalse();
    }
}
