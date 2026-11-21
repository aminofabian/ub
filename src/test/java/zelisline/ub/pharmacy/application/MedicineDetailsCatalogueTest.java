package zelisline.ub.pharmacy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.catalog.application.PackageVariantStockResolver;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.pharmacy.domain.DispensingClass;
import zelisline.ub.pharmacy.domain.ItemMedicineDetails;
import zelisline.ub.pharmacy.repository.ItemMedicineDetailsRepository;

/** Adoption from the platform catalogue and confirming its suggested class (ADR 0033). */
class MedicineDetailsCatalogueTest {

    private static final String BIZ = "biz-1";

    private final ItemMedicineDetailsRepository repository = mock(ItemMedicineDetailsRepository.class);
    private final ItemRepository itemRepository = mock(ItemRepository.class);
    private final PackageVariantStockResolver resolver = mock(PackageVariantStockResolver.class);
    private final MedicineDetailsService service = new MedicineDetailsService(
            repository, itemRepository, resolver, mock(AuditEventPublisher.class), mock(AuditEventBuilder.class));

    private static Item item(String id, String businessId) {
        Item i = new Item();
        i.setId(id);
        i.setBusinessId(businessId);
        i.setName("Amoxil 500mg capsules");
        return i;
    }

    private static CatalogueMedicineTemplate template(DispensingClass suggested) {
        return new CatalogueMedicineTemplate("Amoxicillin", "500 mg", "Capsule", "21", "GSK", suggested);
    }

    @Test
    void anAdoptedMedicineStartsUnclassifiedAndOnlyCarriesTheSuggestion() {
        Item item = item("item-1", BIZ);
        when(repository.findByItemIdAndBusinessId("item-1", BIZ)).thenReturn(Optional.empty());
        when(resolver.stockHolderItemId(item)).thenReturn("item-1");
        org.mockito.ArgumentCaptor<ItemMedicineDetails> saved =
                org.mockito.ArgumentCaptor.forClass(ItemMedicineDetails.class);

        assertTrue(service.markFromCatalogue(
                BIZ, item, template(DispensingClass.PRESCRIPTION_ONLY), true, "user-1"));

        verify(repository).save(saved.capture());
        ItemMedicineDetails d = saved.getValue();
        assertEquals(DispensingClass.UNCLASSIFIED, d.getDispensingClass());
        assertEquals(DispensingClass.PRESCRIPTION_ONLY, d.getSuggestedClass());
        assertNull(d.getClassSetBy());
        assertEquals("Amoxicillin", d.getGenericName());
        assertEquals(BIZ, d.getBusinessId());
        assertTrue(item.isHasExpiry());
    }

    @Test
    void anItemFromAnotherBusinessIsNeverMarked() {
        Item item = item("item-1", "biz-2");
        assertFalse(service.markFromCatalogue(BIZ, item, template(DispensingClass.OTC), true, "user-1"));
        verify(repository, never()).save(any());
    }

    @Test
    void mergingIntoAProductTheShopAlreadySellsDoesNotMarkIt() {
        Item item = item("item-1", BIZ);
        when(repository.findByItemIdAndBusinessId("item-1", BIZ)).thenReturn(Optional.empty());
        assertFalse(service.markFromCatalogue(BIZ, item, template(DispensingClass.OTC), false, "user-1"));
        verify(repository, never()).save(any());
    }

    @Test
    void anExistingMedicineKeepsItsClassAndItsOwnDetails() {
        Item item = item("item-1", BIZ);
        ItemMedicineDetails existing = new ItemMedicineDetails();
        existing.setItemId("item-1");
        existing.setBusinessId(BIZ);
        existing.setDispensingClass(DispensingClass.OTC);
        existing.setGenericName("Amoxycillin");
        when(repository.findByItemIdAndBusinessId("item-1", BIZ)).thenReturn(Optional.of(existing));

        assertTrue(service.markFromCatalogue(BIZ, item, template(DispensingClass.CONTROLLED), false, "user-1"));

        assertEquals(DispensingClass.OTC, existing.getDispensingClass());
        assertNull(existing.getSuggestedClass());
        assertEquals("Amoxycillin", existing.getGenericName());
        assertEquals("500 mg", existing.getStrength());
    }

    @Test
    void confirmingSetsTheSuggestedClassAndRecordsWhoConfirmed() {
        ItemMedicineDetails suggested = new ItemMedicineDetails();
        suggested.setItemId("item-1");
        suggested.setBusinessId(BIZ);
        suggested.setSuggestedClass(DispensingClass.PRESCRIPTION_ONLY);
        ItemMedicineDetails noSuggestion = new ItemMedicineDetails();
        noSuggestion.setItemId("item-2");
        noSuggestion.setBusinessId(BIZ);
        ItemMedicineDetails alreadySet = new ItemMedicineDetails();
        alreadySet.setItemId("item-3");
        alreadySet.setBusinessId(BIZ);
        alreadySet.setDispensingClass(DispensingClass.OTC);
        alreadySet.setSuggestedClass(DispensingClass.CONTROLLED);
        Set<String> ids = new java.util.LinkedHashSet<>(List.of("item-1", "item-2", "item-3", "other-shop"));
        when(itemRepository.findByIdInAndBusinessIdAndDeletedAtIsNull(ids, BIZ))
                .thenReturn(List.of(item("item-1", BIZ), item("item-2", BIZ), item("item-3", BIZ)));
        when(repository.findByBusinessIdAndItemIdIn(BIZ, ids))
                .thenReturn(List.of(suggested, noSuggestion, alreadySet));

        var result = service.confirmSuggested(
                BIZ, List.of("item-1", "item-2", "item-3", "other-shop"), "pharmacist-1");

        assertEquals(1, result.changed());
        assertEquals(3, result.skipped());
        assertEquals(DispensingClass.PRESCRIPTION_ONLY, suggested.getDispensingClass());
        assertEquals("pharmacist-1", suggested.getClassSetBy());
        assertEquals(DispensingClass.UNCLASSIFIED, noSuggestion.getDispensingClass());
        assertEquals(DispensingClass.OTC, alreadySet.getDispensingClass());
    }
}
