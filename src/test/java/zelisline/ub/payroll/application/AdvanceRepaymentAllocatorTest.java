package zelisline.ub.payroll.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class AdvanceRepaymentAllocatorTest {

    @Test
    void allocatesFullAmountsOldestFirstUntilPoolExhausted() {
        var advances = List.of(
                new AdvanceRepaymentAllocator.AdvanceBalance("a1", new BigDecimal("3000.00")),
                new AdvanceRepaymentAllocator.AdvanceBalance("a2", new BigDecimal("5000.00"))
        );

        var allocations = AdvanceRepaymentAllocator.allocate(new BigDecimal("7000.00"), advances);

        assertThat(allocations).hasSize(2);
        assertThat(allocations.get(0).advanceId()).isEqualTo("a1");
        assertThat(allocations.get(0).amount()).isEqualByComparingTo("3000.00");
        assertThat(allocations.get(1).advanceId()).isEqualTo("a2");
        assertThat(allocations.get(1).amount()).isEqualByComparingTo("4000.00");
        assertThat(AdvanceRepaymentAllocator.totalAllocated(allocations))
                .isEqualByComparingTo("7000.00");
    }

    @Test
    void partiallyRepaysOldestAdvanceWhenPoolIsSmaller() {
        var advances = List.of(
                new AdvanceRepaymentAllocator.AdvanceBalance("a1", new BigDecimal("8000.00"))
        );

        var allocations = AdvanceRepaymentAllocator.allocate(new BigDecimal("2500.00"), advances);

        assertThat(allocations).hasSize(1);
        assertThat(allocations.get(0).amount()).isEqualByComparingTo("2500.00");
    }

    @Test
    void returnsEmptyWhenPoolIsZero() {
        var advances = List.of(
                new AdvanceRepaymentAllocator.AdvanceBalance("a1", new BigDecimal("1000.00"))
        );

        assertThat(AdvanceRepaymentAllocator.allocate(BigDecimal.ZERO, advances)).isEmpty();
    }
}
