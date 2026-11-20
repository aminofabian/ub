package zelisline.ub.pharmacy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.domain.LedgerAccount;
import zelisline.ub.finance.repository.JournalEntryRepository;
import zelisline.ub.finance.repository.JournalLineRepository;
import zelisline.ub.finance.repository.LedgerAccountRepository;
import zelisline.ub.identity.domain.Permission;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.RolePermission;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.RolePermissionRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.pharmacy.domain.DispensingClass;
import zelisline.ub.pharmacy.domain.DispensingRecord;
import zelisline.ub.pharmacy.domain.DispensingRecordLine;
import zelisline.ub.pharmacy.domain.ItemMedicineDetails;
import zelisline.ub.pharmacy.repository.DispensingRecordLineRepository;
import zelisline.ub.pharmacy.repository.DispensingRecordRepository;
import zelisline.ub.pharmacy.repository.ItemMedicineDetailsRepository;
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.purchasing.repository.StockMovementRepository;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.sales.repository.SalePaymentRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Drives real sales through {@code POST /api/v1/sales} for a product marked as a medicine
 * (ADR 0033): the dispensing gate and the strict stock rules, end to end. The seed follows
 * {@code SaleSlice2IT}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PharmacySaleGateIT {

    private static final String TENANT = "dddddddd-dddd-dddd-dddd-dddddddd0033";
    private static final String ROLE_CASHIER = "22222222-0000-0000-0000-0000000033c1";
    private static final String ROLE_PHARMACIST = "22222222-0000-0000-0000-0000000033c2";
    private static final String BATCH_1 = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb3301";
    private static final String BATCH_2 = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb3302";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private BusinessRepository businessRepository;
    @Autowired
    private BranchRepository branchRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PermissionRepository permissionRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private RolePermissionRepository rolePermissionRepository;
    @Autowired
    private ItemTypeRepository itemTypeRepository;
    @Autowired
    private ItemRepository itemRepository;
    @Autowired
    private InventoryBatchRepository inventoryBatchRepository;
    @Autowired
    private StockMovementRepository stockMovementRepository;
    @Autowired
    private ShiftRepository shiftRepository;
    @Autowired
    private SaleRepository saleRepository;
    @Autowired
    private SaleItemRepository saleItemRepository;
    @Autowired
    private SalePaymentRepository salePaymentRepository;
    @Autowired
    private JournalLineRepository journalLineRepository;
    @Autowired
    private JournalEntryRepository journalEntryRepository;
    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;
    @Autowired
    private CatalogBootstrapService catalogBootstrapService;
    @Autowired
    private ItemCatalogService itemCatalogService;
    @Autowired
    private ItemMedicineDetailsRepository medicineDetailsRepository;
    @Autowired
    private DispensingRecordRepository dispensingRecordRepository;
    @Autowired
    private DispensingRecordLineRepository dispensingRecordLineRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User cashier;
    private User pharmacist;
    private String branchId;
    private String itemId;

    @BeforeEach
    void seed() {
        dispensingRecordLineRepository.deleteAll();
        dispensingRecordRepository.deleteAll();
        medicineDetailsRepository.deleteAll();
        salePaymentRepository.deleteAll();
        saleItemRepository.deleteAll();
        saleRepository.deleteAll();
        journalLineRepository.deleteAll();
        journalEntryRepository.deleteAll();
        ledgerAccountRepository.deleteAll();
        stockMovementRepository.deleteAll();
        shiftRepository.deleteAll();
        inventoryBatchRepository.deleteAll();
        itemRepository.deleteAll();
        itemTypeRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Chemist");
        b.setSlug("chemist-gate");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Counter");
        branchRepository.save(br);
        branchId = br.getId();

        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT);
        String goodsTypeId = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT).getFirst().getId();

        String[] cashierKeys = {"shifts.open", "shifts.close", "shifts.read", "sales.sell", "sales.void.any"};
        String[] pharmacistOnly = {"pharmacy.dispense", "pharmacy.records.read"};
        role(ROLE_CASHIER, "rx_gate_cashier");
        role(ROLE_PHARMACIST, "rx_gate_pharmacist");
        int n = 0;
        for (String key : cashierKeys) {
            String id = permission(n++, key);
            grant(ROLE_CASHIER, id);
            grant(ROLE_PHARMACIST, id);
        }
        for (String key : pharmacistOnly) {
            grant(ROLE_PHARMACIST, permission(n++, key));
        }
        cashier = userRepository.save(user("cashier@rx-gate", ROLE_CASHIER));
        pharmacist = userRepository.save(user("pharmacist@rx-gate", ROLE_PHARMACIST));

        itemId = itemCatalogService.createItem(
                TENANT,
                new CreateItemRequest(
                        "SKU-RX", null, "Amoxicillin 500mg", null, goodsTypeId, null, null, null,
                        false, true, true,
                        null, null, null, null, null, null, null, null, null, null, true, null, null, null, null),
                null
        ).body().id();
        var item = itemRepository.findById(itemId).orElseThrow();
        item.setHasExpiry(true);
        item.setCurrentStock(new BigDecimal("10"));
        itemRepository.save(item);
        Instant base = Instant.parse("2026-02-10T12:00:00Z");
        inventoryBatchRepository.save(batch(BATCH_1, base, LocalDate.of(2030, 4, 1), "LOT-A"));
        inventoryBatchRepository.save(batch(BATCH_2, base.plusSeconds(600), LocalDate.of(2030, 9, 1), "LOT-B"));

        seedLedger();
    }

    @Test
    void anUnclassifiedMedicineCannotBeSold() throws Exception {
        markMedicine(DispensingClass.UNCLASSIFIED);
        openShift(pharmacist, ROLE_PHARMACIST);
        sell(pharmacist, ROLE_PHARMACIST, 1, DETAILS, status().isConflict());
        assertEquals(0, saleRepository.count());
        assertEquals(0, dispensingRecordRepository.count());
    }

    @Test
    void anOverTheCounterMedicineSellsLikeAnyProductAndLeavesNoRecord() throws Exception {
        markMedicine(DispensingClass.OTC);
        openShift(cashier, ROLE_CASHIER);
        sell(cashier, ROLE_CASHIER, 2, null, status().isCreated());
        assertEquals(1, saleRepository.count());
        assertEquals(0, dispensingRecordRepository.count());
    }

    @Test
    void aPrescriptionMedicineNeedsDetailsThenWritesOneRecordNamingTheDispenserAndTheLot() throws Exception {
        markMedicine(DispensingClass.PRESCRIPTION_ONLY);
        openShift(pharmacist, ROLE_PHARMACIST);

        sell(pharmacist, ROLE_PHARMACIST, 2, null, status().isConflict());
        assertEquals(0, saleRepository.count());

        MvcResult created = sell(pharmacist, ROLE_PHARMACIST, 2, DETAILS, status().isCreated());
        String saleId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        List<DispensingRecord> records = dispensingRecordRepository.findByBusinessIdAndSaleId(TENANT, saleId);
        assertEquals(1, records.size());
        DispensingRecord record = records.getFirst();
        assertEquals(pharmacist.getId(), record.getDispensedBy());
        assertEquals("Dr Wanjiru", record.getPrescriberName());
        List<DispensingRecordLine> lines = dispensingRecordLineRepository
                .findByBusinessIdAndRecordIdInOrderByCreatedAtAscIdAsc(TENANT, List.of(record.getId()));
        assertEquals(1, lines.size());
        assertEquals(0, new BigDecimal("2").compareTo(lines.getFirst().getQuantity()));
        assertEquals("LOT-A", lines.getFirst().getLotNumbers());
        assertEquals("1 three times a day", lines.getFirst().getInstructions());

        // Voiding the sale keeps the record and marks it.
        mockMvc.perform(as(post("/api/v1/sales/{saleId}/void", saleId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"notes\":\"wrong patient\"}"), pharmacist, ROLE_PHARMACIST))
                .andExpect(status().isOk());
        DispensingRecord after = dispensingRecordRepository.findById(record.getId()).orElseThrow();
        assertTrue(after.getVoidedAt() != null);
    }

    @Test
    void aCashierWithoutApprovalCannotCompleteAPrescriptionSale() throws Exception {
        markMedicine(DispensingClass.PRESCRIPTION_ONLY);
        openShift(cashier, ROLE_CASHIER);
        sell(cashier, ROLE_CASHIER, 1, DETAILS, status().isForbidden());
        assertEquals(0, saleRepository.count());
        assertEquals(0, dispensingRecordRepository.count());
        assertEquals(0, new BigDecimal("5").compareTo(
                inventoryBatchRepository.findById(BATCH_1).orElseThrow().getQuantityRemaining()));
    }

    @Test
    void aMedicineIsNeverSoldBeyondItsStockOrFromAnUndatedLot() throws Exception {
        markMedicine(DispensingClass.OTC);
        openShift(cashier, ROLE_CASHIER);

        MvcResult oversell = sell(cashier, ROLE_CASHIER, 11, null, null);
        assertFalse(oversell.getResponse().getStatus() < 400, "selling 11 of 10 must be refused");
        assertEquals(0, saleRepository.count());

        for (String id : List.of(BATCH_1, BATCH_2)) {
            InventoryBatch batch = inventoryBatchRepository.findById(id).orElseThrow();
            batch.setExpiryDate(null);
            inventoryBatchRepository.save(batch);
        }
        sell(cashier, ROLE_CASHIER, 1, null, status().isBadRequest());
        assertEquals(0, saleRepository.count());
    }

    private static final String DETAILS =
            "{\"prescriberName\":\"Dr Wanjiru\",\"prescriptionRef\":\"RX-1\",\"lines\":[{\"itemId\":\"%s\",\"instructions\":\"1 three times a day\"}]}";

    private MvcResult sell(User user, String roleId, int quantity, String dispensing, ResultMatcher expected)
            throws Exception {
        String body = "{\"branchId\":\"%s\",\"lines\":[{\"itemId\":\"%s\",\"quantity\":%d,\"unitPrice\":5}],"
                .formatted(branchId, itemId, quantity)
                + "\"payments\":[{\"method\":\"cash\",\"amount\":%d}]".formatted(quantity * 5)
                + (dispensing == null ? "" : ",\"dispensing\":" + dispensing.formatted(itemId))
                + "}";
        var actions = mockMvc.perform(as(post("/api/v1/sales")
                .contentType(APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", "idem-" + UUID.randomUUID()), user, roleId));
        if (expected != null) {
            actions.andExpect(expected);
        }
        return actions.andReturn();
    }

    private void openShift(User user, String roleId) throws Exception {
        mockMvc.perform(as(post("/api/v1/shifts/open")
                        .contentType(APPLICATION_JSON)
                        .content("{\"branchId\":\"%s\",\"openingCash\":100.00}".formatted(branchId)), user, roleId))
                .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder as(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            User user,
            String roleId) {
        return request
                .header("X-Tenant-Id", TENANT)
                .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                .header(TestAuthenticationFilter.HEADER_ROLE_ID, roleId);
    }

    private void markMedicine(DispensingClass dispensingClass) {
        ItemMedicineDetails d = new ItemMedicineDetails();
        d.setItemId(itemId);
        d.setBusinessId(TENANT);
        d.setDispensingClass(dispensingClass);
        medicineDetailsRepository.save(d);
    }

    private InventoryBatch batch(String id, Instant received, LocalDate expiry, String lot) {
        InventoryBatch b = new InventoryBatch();
        b.setId(id);
        b.setBusinessId(TENANT);
        b.setBranchId(branchId);
        b.setItemId(itemId);
        b.setSupplierId(null);
        b.setBatchNumber("BN-" + id.substring(28));
        b.setLotNumber(lot);
        b.setSourceType("test");
        b.setSourceId(UUID.randomUUID().toString());
        b.setInitialQuantity(new BigDecimal("5"));
        b.setQuantityRemaining(new BigDecimal("5"));
        b.setUnitCost(new BigDecimal("2.00"));
        b.setReceivedAt(received);
        b.setExpiryDate(expiry);
        b.setStatus("active");
        return b;
    }

    private void role(String id, String key) {
        Role r = new Role();
        r.setId(id);
        r.setBusinessId(null);
        r.setRoleKey(key);
        r.setName(key);
        r.setSystem(true);
        roleRepository.save(r);
    }

    private String permission(int n, String key) {
        Permission p = new Permission();
        p.setId("11111111-0000-0000-0000-0000000034" + String.format("%02d", n));
        p.setPermissionKey(key);
        p.setDescription(key);
        permissionRepository.save(p);
        return p.getId();
    }

    private void grant(String roleId, String permissionId) {
        RolePermission rp = new RolePermission();
        rp.setId(new RolePermission.Id(roleId, permissionId));
        rolePermissionRepository.save(rp);
    }

    private User user(String email, String roleId) {
        User u = new User();
        u.setBusinessId(TENANT);
        u.setEmail(email);
        u.setName(email);
        u.setRoleId(roleId);
        u.setBranchId(branchId);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        return u;
    }

    private void seedLedger() {
        String[][] rows = {
                {LedgerAccountCodes.OPERATING_CASH, "Operating cash", "asset"},
                {LedgerAccountCodes.INVENTORY, "Inventory", "asset"},
                {LedgerAccountCodes.ACCOUNTS_RECEIVABLE_CUSTOMERS, "AR", "asset"},
                {LedgerAccountCodes.CUSTOMER_WALLET_LIABILITY, "Wallet", "liability"},
                {LedgerAccountCodes.MPESA_CLEARING, "M-Pesa clearing", "asset"},
                {LedgerAccountCodes.CARD_CLEARING, "Card clearing", "asset"},
                {LedgerAccountCodes.SALES_REVENUE, "Sales revenue", "revenue"},
                {LedgerAccountCodes.COST_OF_GOODS_SOLD, "COGS", "expense"},
                {LedgerAccountCodes.INVENTORY_SHRINKAGE, "Inventory shrinkage", "expense"},
        };
        for (String[] row : rows) {
            LedgerAccount a = new LedgerAccount();
            a.setBusinessId(TENANT);
            a.setCode(row[0]);
            a.setName(row[1]);
            a.setAccountType(row[2]);
            ledgerAccountRepository.save(a);
        }
    }
}
