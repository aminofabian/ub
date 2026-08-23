package zelisline.ub.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import zelisline.ub.inventory.application.RestockDigestFormula.Computed;
import zelisline.ub.inventory.application.RestockDigestFormula.LinkInput;
import zelisline.ub.inventory.application.RestockDigestFormula.VelocityInput;

class RestockDigestFormulaTest {

    private static final VelocityInput RICH =
            new VelocityInput(new BigDecimal("70"), new BigDecimal("300"), 20);

    private static final LinkInput NO_LINK = null;

    @Test
    void belowMinWithVelocity_producesLineAndHighConfidence() {
        Optional<Computed> result = RestockDigestFormula.compute(
                new BigDecimal("8"),
                BigDecimal.ZERO,
                new BigDecimal("12"),
                null,
                RICH,
                NO_LINK,
                3,
                false,
                false);

        assertThat(result).isPresent();
        Computed c = result.get();
        // avgDaily = 0.7*(70/7) + 0.3*((300-70)/23) = 7 + 3 = 10
        // par = ceil(10 * (3 + 1)) = 40 → suggested = 40 - 8 = 32
        assertThat(c.par()).isEqualByComparingTo("40");
        assertThat(c.suggestedQty()).isEqualByComparingTo("32");
        assertThat(c.confidence()).isEqualTo("high");
        assertThat(c.reasonCode()).contains("BELOW_MIN");
        assertThat(c.evidence()).contains("Sold 10.0/day");
    }

    @Test
    void inboundReducesSuggestedQty() {
        Optional<Computed> noInbound = RestockDigestFormula.compute(
                new BigDecimal("8"), BigDecimal.ZERO, new BigDecimal("12"), null,
                RICH, NO_LINK, 3, false, false);
        Optional<Computed> withInbound = RestockDigestFormula.compute(
                new BigDecimal("8"), new BigDecimal("20"), new BigDecimal("12"), null,
                RICH, NO_LINK, 3, false, false);

        assertThat(noInbound).isPresent();
        assertThat(withInbound).isPresent();
        // par 40; effective 28 → 12 (vs 32 without inbound)
        assertThat(withInbound.get().suggestedQty()).isEqualByComparingTo("12");
        assertThat(withInbound.get().suggestedQty())
                .isLessThan(noInbound.get().suggestedQty());
    }

    @Test
    void inboundCoversPar_suppressed() {
        Optional<Computed> result = RestockDigestFormula.compute(
                new BigDecimal("8"), new BigDecimal("40"), new BigDecimal("12"), null,
                RICH, NO_LINK, 3, false, false);

        assertThat(result).isEmpty();
    }

    @Test
    void packRounding_roundsUpToPack() {
        Optional<Computed> result = RestockDigestFormula.compute(
                new BigDecimal("7"),
                BigDecimal.ZERO,
                new BigDecimal("12"),
                null,
                RICH,
                new LinkInput(new BigDecimal("6"), null, 2),
                3,
                false,
                false);

        assertThat(result).isPresent();
        // avgDaily = 10 → par = ceil(10 * (3+2)) = 50 → suggested = 43 → round to 48 (6×8)
        assertThat(result.get().suggestedQty()).isEqualByComparingTo("48");
    }

    @Test
    void minOrderQtyLiftsSmallSuggested() {
        Optional<Computed> result = RestockDigestFormula.compute(
                new BigDecimal("8"),
                BigDecimal.ZERO,
                new BigDecimal("12"),
                new BigDecimal("10"),
                RICH,
                new LinkInput(null, new BigDecimal("20"), 1),
                3,
                false,
                false);

        assertThat(result).isPresent();
        // par = manual 10 → suggested = 2 → lifted to MOQ 20; BELOW_MIN fires (8 <= 12)
        assertThat(result.get().suggestedQty()).isEqualByComparingTo("20");
    }

    @Test
    void thinHistory_usesThresholdFallbackAndLowConfidence() {
        VelocityInput thin = new VelocityInput(new BigDecimal("2"), new BigDecimal("3"), 2);

        Optional<Computed> result = RestockDigestFormula.compute(
                new BigDecimal("4"),
                BigDecimal.ZERO,
                new BigDecimal("10"),
                null,
                thin,
                NO_LINK,
                3,
                false,
                false);

        assertThat(result).isPresent();
        // fallback par = reorderLevel * 2 = 20 → suggested = 16
        assertThat(result.get().par()).isEqualByComparingTo("20");
        assertThat(result.get().suggestedQty()).isEqualByComparingTo("16");
        assertThat(result.get().confidence()).isEqualTo("low");
    }

    @Test
    void manualParWinsOverVelocity() {
        Optional<Computed> result = RestockDigestFormula.compute(
                new BigDecimal("8"),
                BigDecimal.ZERO,
                new BigDecimal("12"),
                new BigDecimal("24"),
                RICH,
                NO_LINK,
                3,
                false,
                false);

        assertThat(result).isPresent();
        assertThat(result.get().par()).isEqualByComparingTo("24");
        assertThat(result.get().suggestedQty()).isEqualByComparingTo("16");
    }

    @Test
    void stockOutRecovery_reasonFiresForCountedZeroItem() {
        Optional<Computed> result = RestockDigestFormula.compute(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("12"),
                null,
                RICH,
                NO_LINK,
                3,
                true,
                false);

        assertThat(result).isPresent();
        assertThat(result.get().reasonCode()).contains("STOCKOUT_RECOVERY");
    }

    @Test
    void deadStock_withoutVelocityOrStockOut_isSkipped() {
        // Zero sales, no stock-out days, no threshold — cannot compute → no line.
        Optional<Computed> result = RestockDigestFormula.compute(
                new BigDecimal("50"),
                BigDecimal.ZERO,
                null,
                null,
                new VelocityInput(BigDecimal.ZERO, BigDecimal.ZERO, 0),
                NO_LINK,
                3,
                false,
                false);

        assertThat(result).isEmpty();
    }

    @Test
    void snoozedItem_isSuppressed() {
        Optional<Computed> result = RestockDigestFormula.compute(
                new BigDecimal("8"),
                BigDecimal.ZERO,
                new BigDecimal("12"),
                null,
                RICH,
                NO_LINK,
                3,
                false,
                true);

        assertThat(result).isEmpty();
    }

    @Test
    void alreadyCovered_effectiveAbovePar_suppressed() {
        Optional<Computed> result = RestockDigestFormula.compute(
                new BigDecimal("60"),
                BigDecimal.ZERO,
                new BigDecimal("12"),
                null,
                RICH,
                NO_LINK,
                3,
                false,
                false);

        assertThat(result).isEmpty();
    }
}
