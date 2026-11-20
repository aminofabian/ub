package zelisline.ub.pharmacy.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.catalog.application.PackageVariantStockResolver;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.pharmacy.domain.DispensingApproval;
import zelisline.ub.pharmacy.domain.DispensingClass;
import zelisline.ub.pharmacy.domain.ItemMedicineDetails;
import zelisline.ub.pharmacy.domain.PharmacyProfile;
import zelisline.ub.pharmacy.repository.DispensingApprovalRepository;
import zelisline.ub.pharmacy.repository.DispensingRecordLineRepository;
import zelisline.ub.pharmacy.repository.DispensingRecordRepository;
import zelisline.ub.pharmacy.repository.ItemMedicineDetailsRepository;
import zelisline.ub.pharmacy.repository.PharmacyProfileRepository;
import zelisline.ub.platform.web.SetupRequiredException;
import zelisline.ub.platform.web.SetupRequirement;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.sales.api.dto.PostSaleDispensingRequest;
import zelisline.ub.sales.api.dto.PostSaleLineRequest;

class DispensingGateTest {

    private static final String BIZ = "biz-1";
    private static final String CASHIER = "user-cashier";
    private static final String CASHIER_ROLE = "role-cashier";
    private static final String PHARMACIST = "user-pharmacist";
    private static final String PHARMACIST_ROLE = "role-pharmacist";
    private static final String ITEM = "item-amoxil";

    private final ItemRepository itemRepository = mock(ItemRepository.class);
    private final PackageVariantStockResolver stockResolver = mock(PackageVariantStockResolver.class);
    private final ItemMedicineDetailsRepository medicineRepository = mock(ItemMedicineDetailsRepository.class);
    private final PermissionRepository permissionRepository = mock(PermissionRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PharmacyProfileRepository profileRepository = mock(PharmacyProfileRepository.class);
    private final DispensingApprovalRepository approvalRepository = mock(DispensingApprovalRepository.class);

    private final DispensingGate gate = new DispensingGate(
            itemRepository,
            stockResolver,
            new MedicineRegistry(medicineRepository),
            permissionRepository,
            userRepository,
            profileRepository,
            approvalRepository,
            mock(DispensingRecordRepository.class),
            mock(DispensingRecordLineRepository.class),
            mock(InventoryBatchRepository.class),
            mock(AuditEventPublisher.class),
            mock(AuditEventBuilder.class),
            mock(zelisline.ub.pharmacy.repository.PharmacyPatientRepository.class));

    private void medicine(DispensingClass cls) {
        Item item = new Item();
        item.setId(ITEM);
        item.setBusinessId(BIZ);
        item.setName("Amoxil 500mg");
        when(itemRepository.findByIdInAndBusinessIdAndDeletedAtIsNull(anyCollection(), eq(BIZ)))
                .thenReturn(List.of(item));
        when(stockResolver.stockHolderItemId(any())).thenReturn(ITEM);
        if (cls == null) {
            when(medicineRepository.findByBusinessIdAndItemIdIn(eq(BIZ), anyCollection())).thenReturn(List.of());
            return;
        }
        ItemMedicineDetails d = new ItemMedicineDetails();
        d.setItemId(ITEM);
        d.setBusinessId(BIZ);
        d.setDispensingClass(cls);
        when(medicineRepository.findByBusinessIdAndItemIdIn(eq(BIZ), anyCollection())).thenReturn(List.of(d));
        when(permissionRepository.findPermissionKeysByRoleId(CASHIER_ROLE)).thenReturn(List.of("sales.sell"));
        when(permissionRepository.findPermissionKeysByRoleId(PHARMACIST_ROLE))
                .thenReturn(List.of("sales.sell", DispensingGate.PERMISSION_DISPENSE));
        when(profileRepository.findById(BIZ)).thenReturn(Optional.empty());
    }

    private static List<PostSaleLineRequest> cart() {
        return cart(BigDecimal.ONE);
    }

    private static List<PostSaleLineRequest> cart(BigDecimal quantity) {
        return List.of(new PostSaleLineRequest(
                ITEM, quantity, new BigDecimal("250"), null, null, null, null, null, null, null, null,
                null, null, null));
    }

    private static PostSaleDispensingRequest details(String approvalId) {
        return new PostSaleDispensingRequest("Dr Wanjiru", null, "RX-1", null, null, null, approvalId, null);
    }

    private static SetupRequiredException blocked(Runnable call) {
        SetupRequiredException ex = assertThrows(SetupRequiredException.class, call::run);
        assertEquals(SetupRequirement.DISPENSING, ex.getRequirement());
        return ex;
    }

    @Test
    void anOrdinaryProductIsNotGated() {
        medicine(null);
        assertNull(gate.evaluate(BIZ, cart(), null, CASHIER, CASHIER_ROLE));
    }

    @Test
    void anOtcMedicineSellsWithoutARecord() {
        medicine(DispensingClass.OTC);
        assertNull(gate.evaluate(BIZ, cart(), null, CASHIER, CASHIER_ROLE));
    }

    @Test
    void anUnclassifiedMedicineCannotBeSold() {
        medicine(DispensingClass.UNCLASSIFIED);
        SetupRequiredException ex = blocked(() -> gate.evaluate(BIZ, cart(), details(null), PHARMACIST, PHARMACIST_ROLE));
        assertEquals(DispensingGate.HINT_CLASSIFY, ex.getHint());
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void aPrescriptionMedicineNeedsARecord() {
        medicine(DispensingClass.PRESCRIPTION_ONLY);
        assertEquals(DispensingGate.HINT_RECORD,
                blocked(() -> gate.evaluate(BIZ, cart(), null, PHARMACIST, PHARMACIST_ROLE)).getHint());
        PostSaleDispensingRequest blankPrescriber =
                new PostSaleDispensingRequest("  ", null, null, null, null, null, null, null);
        assertEquals(DispensingGate.HINT_RECORD,
                blocked(() -> gate.evaluate(BIZ, cart(), blankPrescriber, PHARMACIST, PHARMACIST_ROLE)).getHint());
    }

    @Test
    void aPharmacistDispensesInTheirOwnName() {
        medicine(DispensingClass.PRESCRIPTION_ONLY);
        DispensingGate.Plan plan = gate.evaluate(BIZ, cart(), details(null), PHARMACIST, PHARMACIST_ROLE);
        assertNotNull(plan);
        assertEquals(PHARMACIST, plan.dispenserUserId());
        assertEquals(PHARMACIST, plan.sellerUserId());
        assertNull(plan.approvalId());
        assertTrue(plan.gatedByItemId().containsKey(ITEM));
    }

    @Test
    void aCashierNeedsAPharmacistsApproval() {
        medicine(DispensingClass.PRESCRIPTION_ONLY);
        SetupRequiredException ex = blocked(() -> gate.evaluate(BIZ, cart(), details(null), CASHIER, CASHIER_ROLE));
        assertEquals(DispensingGate.HINT_APPROVE, ex.getHint());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    private DispensingApproval approval(String requestedBy, String itemIds, Instant expiresAt, Instant usedAt) {
        DispensingApproval a = new DispensingApproval();
        a.setId("approval-1");
        a.setBusinessId(BIZ);
        a.setApproverUserId(PHARMACIST);
        a.setRequestedBy(requestedBy);
        a.setItemIds(itemIds);
        a.setExpiresAt(expiresAt);
        a.setUsedAt(usedAt);
        when(approvalRepository.findByIdAndBusinessIdForUpdate("approval-1", BIZ)).thenReturn(Optional.of(a));
        return a;
    }

    @Test
    void aValidApprovalMakesThePharmacistTheDispenser() {
        medicine(DispensingClass.PRESCRIPTION_ONLY);
        approval(CASHIER, ITEM + "=1,item-other=3", Instant.now().plusSeconds(600), null);
        DispensingGate.Plan plan = gate.evaluate(BIZ, cart(), details("approval-1"), CASHIER, CASHIER_ROLE);
        assertEquals(PHARMACIST, plan.dispenserUserId());
        assertEquals(CASHIER, plan.sellerUserId());
        assertEquals("approval-1", plan.approvalId());
    }

    @Test
    void anApprovalIsRefusedWhenExpiredUsedForeignOrForOtherMedicines() {
        medicine(DispensingClass.PRESCRIPTION_ONLY);

        approval(CASHIER, ITEM + "=1", Instant.now().minusSeconds(5), null);
        assertEquals(DispensingGate.HINT_APPROVE,
                blocked(() -> gate.evaluate(BIZ, cart(), details("approval-1"), CASHIER, CASHIER_ROLE)).getHint());

        approval(CASHIER, ITEM + "=1", Instant.now().plusSeconds(600), Instant.now());
        blocked(() -> gate.evaluate(BIZ, cart(), details("approval-1"), CASHIER, CASHIER_ROLE));

        approval("another-cashier", ITEM + "=1", Instant.now().plusSeconds(600), null);
        blocked(() -> gate.evaluate(BIZ, cart(), details("approval-1"), CASHIER, CASHIER_ROLE));

        approval(CASHIER, "item-other=1", Instant.now().plusSeconds(600), null);
        blocked(() -> gate.evaluate(BIZ, cart(), details("approval-1"), CASHIER, CASHIER_ROLE));
    }

    @Test
    void anApprovalCoversTheQuantityShownAndNoMore() {
        medicine(DispensingClass.PRESCRIPTION_ONLY);

        approval(CASHIER, ITEM + "=2", Instant.now().plusSeconds(600), null);
        assertEquals("approval-1",
                gate.evaluate(BIZ, cart(new BigDecimal("2")), details("approval-1"), CASHIER, CASHIER_ROLE)
                        .approvalId());
        assertEquals("approval-1",
                gate.evaluate(BIZ, cart(BigDecimal.ONE), details("approval-1"), CASHIER, CASHIER_ROLE)
                        .approvalId());
        assertEquals(DispensingGate.HINT_APPROVE,
                blocked(() -> gate.evaluate(
                        BIZ, cart(new BigDecimal("3")), details("approval-1"), CASHIER, CASHIER_ROLE)).getHint());

        // An approval that names the medicine but no quantity approves nothing.
        approval(CASHIER, ITEM, Instant.now().plusSeconds(600), null);
        blocked(() -> gate.evaluate(BIZ, cart(), details("approval-1"), CASHIER, CASHIER_ROLE));
    }

    @Test
    void anApprovalFromAnotherBusinessIsNotFound() {
        medicine(DispensingClass.PRESCRIPTION_ONLY);
        when(approvalRepository.findByIdAndBusinessIdForUpdate("approval-x", BIZ)).thenReturn(Optional.empty());
        blocked(() -> gate.evaluate(BIZ, cart(), details("approval-x"), CASHIER, CASHIER_ROLE));
        verify(approvalRepository).findByIdAndBusinessIdForUpdate("approval-x", BIZ);
    }

    @Test
    void aControlledMedicineIsBlockedUntilTheShopSwitchesItOn() {
        medicine(DispensingClass.CONTROLLED);
        assertEquals(DispensingGate.HINT_CONTROLLED,
                blocked(() -> gate.evaluate(BIZ, cart(), details(null), PHARMACIST, PHARMACIST_ROLE)).getHint());

        PharmacyProfile profile = new PharmacyProfile();
        profile.setBusinessId(BIZ);
        profile.setControlledSalesEnabled(true);
        when(profileRepository.findById(BIZ)).thenReturn(Optional.of(profile));
        assertNotNull(gate.evaluate(BIZ, cart(), details(null), PHARMACIST, PHARMACIST_ROLE));
    }

    @Test
    void itemsAreLoadedForTheCallersBusinessOnly() {
        medicine(DispensingClass.PRESCRIPTION_ONLY);
        when(itemRepository.findByIdInAndBusinessIdAndDeletedAtIsNull(anyCollection(), eq("biz-2")))
                .thenReturn(List.of());
        assertNull(gate.evaluate("biz-2", cart(), null, CASHIER, CASHIER_ROLE));
    }

    @Test
    void theOnlineShopSellsOnlyOtcMedicines() {
        medicine(DispensingClass.OTC);
        assertDoesNotThrow(() -> gate.assertOnlineSellable(BIZ, List.of(ITEM)));

        medicine(DispensingClass.PRESCRIPTION_ONLY);
        assertEquals(HttpStatus.CONFLICT,
                assertThrows(ResponseStatusException.class, () -> gate.assertOnlineSellable(BIZ, List.of(ITEM)))
                        .getStatusCode());

        medicine(DispensingClass.UNCLASSIFIED);
        assertThrows(ResponseStatusException.class, () -> gate.assertOnlineSellable(BIZ, List.of(ITEM)));
    }
}
