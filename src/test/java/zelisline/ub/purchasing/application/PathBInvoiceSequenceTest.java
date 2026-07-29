package zelisline.ub.purchasing.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class PathBInvoiceSequenceTest {

    @Test
    void startsAtOneWhenShopHasNoSupplies() {
        assertEquals(1L, PathBPurchaseService.nextSequence(List.of()));
    }

    @Test
    void continuesFromHighestSequentialCode() {
        assertEquals(4L, PathBPurchaseService.nextSequence(List.of("PB-1", "PB-3", "PB-2")));
    }

    @Test
    void ignoresLegacySessionIdCodes() {
        // Legacy codes were a slice of the session UUID; the all-digit ones must not be read as a sequence.
        assertEquals(
                1L,
                PathBPurchaseService.nextSequence(List.of("PB-ED29096EE823", "PB-64974384", "PB-6EBCC58AA66F")));
    }

    @Test
    void mixesLegacyAndSequentialCodes() {
        assertEquals(3L, PathBPurchaseService.nextSequence(List.of("PB-64974384", "PB-1", "PB-2")));
    }

    @Test
    void ignoresPaddedAndNonNumericSuffixes() {
        assertEquals(1L, PathBPurchaseService.nextSequence(Arrays.asList("PB-007", "PB-", "PB-12A", null)));
    }
}
