package zelisline.ub.pharmacy.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.pharmacy.repository.ItemMedicineDetailsRepository;

class MedicineReceivingGuardTest {

    private static final String BIZ = "biz-1";

    private final ItemMedicineDetailsRepository repository = mock(ItemMedicineDetailsRepository.class);
    private final MedicineReceivingGuard guard = new MedicineReceivingGuard(new MedicineRegistry(repository));

    @Test
    void anOrdinaryProductNeedsNeitherLotNorExpiry() {
        when(repository.existsByBusinessIdAndItemIdIn(eq(BIZ), anyCollection())).thenReturn(false);
        assertDoesNotThrow(() -> guard.requireLotAndExpiry(BIZ, List.of("item-1"), null, null, "Bread"));
    }

    @Test
    void aMedicineNeedsBoth() {
        when(repository.existsByBusinessIdAndItemIdIn(eq(BIZ), anyCollection())).thenReturn(true);
        LocalDate expiry = LocalDate.now().plusDays(200);

        ResponseStatusException none = assertThrows(ResponseStatusException.class,
                () -> guard.requireLotAndExpiry(BIZ, List.of("item-1"), " ", null, "Amoxil 500"));
        assertEquals(HttpStatus.BAD_REQUEST, none.getStatusCode());
        assertTrue(none.getReason().contains("Amoxil 500"));

        assertThrows(ResponseStatusException.class,
                () -> guard.requireLotAndExpiry(BIZ, List.of("item-1"), null, expiry, "Amoxil 500"));
        assertThrows(ResponseStatusException.class,
                () -> guard.requireLotAndExpiry(BIZ, List.of("item-1"), "LOT42", null, "Amoxil 500"));
        assertDoesNotThrow(() -> guard.requireLotAndExpiry(BIZ, List.of("item-1"), "LOT42", expiry, "Amoxil 500"));
    }

    @Test
    void lotIsTrimmedAndBounded() {
        assertNull(MedicineReceivingGuard.normalizeLot(null));
        assertNull(MedicineReceivingGuard.normalizeLot("   "));
        assertEquals("AB12", MedicineReceivingGuard.normalizeLot("  AB12 "));
        String tooLong = "X".repeat(MedicineReceivingGuard.LOT_MAX_LENGTH + 1);
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> MedicineReceivingGuard.normalizeLot(tooLong))
                        .getStatusCode());
    }
}
