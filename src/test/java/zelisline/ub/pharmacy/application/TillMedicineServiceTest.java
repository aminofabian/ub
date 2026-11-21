package zelisline.ub.pharmacy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import zelisline.ub.catalog.application.PackageVariantStockResolver;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.pharmacy.api.dto.TillMedicineDtos.TillMedicine;
import zelisline.ub.pharmacy.domain.DispensingClass;
import zelisline.ub.pharmacy.domain.ItemMedicineDetails;
import zelisline.ub.pharmacy.repository.ItemMedicineDetailsRepository;
import zelisline.ub.pharmacy.repository.PharmacyProfileRepository;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;

/** What the till shows about a medicine before a sale is charged (ADR 0033). */
class TillMedicineServiceTest {

    private static final String BIZ = "biz-1";
    private static final String BRANCH = "branch-a";

    private final DispensingGate gate = mock(DispensingGate.class);
    private final ItemRepository itemRepository = mock(ItemRepository.class);
    private final PackageVariantStockResolver resolver = mock(PackageVariantStockResolver.class);
    private final MedicineRegistry registry = mock(MedicineRegistry.class);
    private final ItemMedicineDetailsRepository detailsRepository = mock(ItemMedicineDetailsRepository.class);
    private final InventoryBatchRepository batchRepository = mock(InventoryBatchRepository.class);
    private final PharmacyProfileRepository profileRepository = mock(PharmacyProfileRepository.class);
    private final TillMedicineService service = new TillMedicineService(
            gate, itemRepository, resolver, registry, detailsRepository, batchRepository, profileRepository);

    private static InventoryBatch batch(String id, String itemId, String lot, LocalDate expiry) {
        InventoryBatch b = new InventoryBatch();
        b.setId(id);
        b.setItemId(itemId);
        b.setLotNumber(lot);
        b.setExpiryDate(expiry);
        return b;
    }

    @Test
    void showsTheLotTheNextUnitComesFromAndIgnoresUndatedStock() {
        Item item = new Item();
        item.setId("item-1");
        item.setBusinessId(BIZ);
        ItemMedicineDetails d = new ItemMedicineDetails();
        d.setItemId("item-1");
        d.setBusinessId(BIZ);
        d.setGenericName("Amoxicillin");
        d.setDispensingClass(DispensingClass.PRESCRIPTION_ONLY);
        when(gate.standings(BIZ, List.of("item-1", "bread"))).thenReturn(List.of(new DispensingGate.ItemStanding(
                "item-1", "Amoxil", DispensingClass.PRESCRIPTION_ONLY, DispensingGate.ItemStanding.NEEDS_RECORD)));
        when(itemRepository.findByIdInAndBusinessIdAndDeletedAtIsNull(any(), eq(BIZ))).thenReturn(List.of(item));
        when(resolver.stockHolderItemId(item)).thenReturn("item-1");
        when(registry.byItemId(eq(BIZ), any())).thenReturn(Map.of("item-1", d));
        when(profileRepository.findById(BIZ)).thenReturn(Optional.empty());
        LocalDate soon = LocalDate.now().plusDays(20);
        when(batchRepository.findActiveBatchesForItems(eq(BIZ), eq(BRANCH), any(), any(), eq(BigDecimal.ZERO)))
                .thenReturn(List.of(
                        batch("b-late", "item-1", "LATE", LocalDate.now().plusDays(400)),
                        batch("b-undated", "item-1", "NODATE", null),
                        batch("b-soon", "item-1", "SOON", soon)));

        List<TillMedicine> out = service.shelf(BIZ, BRANCH, List.of("item-1", "bread"));

        assertEquals(1, out.size());
        TillMedicine m = out.get(0);
        assertEquals("SOON", m.nextLot());
        assertEquals(soon, m.nextExpiry());
        assertTrue(m.shortDated());
        assertEquals("needs_record", m.status());
        assertEquals("Amoxicillin", m.genericName());
    }

    @Test
    void withoutABranchThereIsNoLotAndNothingIsRead() {
        when(gate.standings(BIZ, List.of("item-1"))).thenReturn(List.of(new DispensingGate.ItemStanding(
                "item-1", "Amoxil", DispensingClass.OTC, DispensingGate.ItemStanding.OK)));
        when(itemRepository.findByIdInAndBusinessIdAndDeletedAtIsNull(any(), eq(BIZ))).thenReturn(List.of());
        when(registry.byItemId(eq(BIZ), any())).thenReturn(Map.of());
        when(profileRepository.findById(BIZ)).thenReturn(Optional.empty());

        TillMedicine m = service.shelf(BIZ, null, List.of("item-1")).get(0);

        assertNull(m.nextLot());
        assertFalse(m.shortDated());
        verify(batchRepository, never()).findActiveBatchesForItems(any(), any(), any(), any(), any());
    }

    @Test
    void aShortSearchNeverReachesTheDatabase() {
        assertTrue(service.searchGeneric(BIZ, "am").isEmpty());
        assertTrue(service.searchGeneric(BIZ, null).isEmpty());
        verify(detailsRepository, never()).searchByGenericName(any(), any(), any());
    }
}
