package zelisline.ub.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.domain.ItemType;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.identity.domain.Permission;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.RolePermission;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.RolePermissionRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.domain.StockMovement;
import zelisline.ub.purchasing.domain.SupplierInvoice;
import zelisline.ub.purchasing.domain.SupplierInvoiceLine;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.purchasing.repository.StockMovementRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceLineRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceRepository;
import zelisline.ub.reporting.api.dto.SupplierMonthlySpendResponse;
import zelisline.ub.reporting.repository.MvSupplierMonthlyRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class SupplierMonthlySpendIT {

    private static final String TENANT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb8";
    private static final String ROLE = "22222222-0000-0000-0000-0000000000b0";

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
    private SupplierRepository supplierRepository;
    @Autowired
    private SupplierInvoiceRepository supplierInvoiceRepository;
    @Autowired
    private SupplierInvoiceLineRepository supplierInvoiceLineRepository;
    @Autowired
    private InventoryBatchRepository inventoryBatchRepository;
    @Autowired
    private StockMovementRepository stockMovementRepository;
    @Autowired
    private MvSupplierMonthlyRepository mvRepository;
    @Autowired
    private MvSupplierMonthlyRefresher refresher;
    @Autowired
    private SupplierReportsService supplierReportsService;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private String branchId;
    private String supplierId;
    private String itemId;
    private final String batchId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1";

    @BeforeEach
    void seedTenant() {
        stockMovementRepository.deleteAll();
        supplierInvoiceLineRepository.deleteAll();
        supplierInvoiceRepository.deleteAll();
        inventoryBatchRepository.deleteAll();
        itemRepository.deleteAll();
        itemTypeRepository.deleteAll();
        supplierRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();
        mvRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Supplier Reports Co");
        b.setSlug("supplier-reports-co");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Main");
        branchRepository.save(br);
        branchId = br.getId();

        String p = "11111111-0000-0000-0000-000000000102";
        permissionRepository.save(perm(p, "reports.suppliers.read", "r"));
        Role role = new Role();
        role.setId(ROLE);
        role.setBusinessId(null);
        role.setRoleKey("sup_rep_test");
        role.setName("Sup Rep Test");
        role.setSystem(true);
        roleRepository.save(role);
        RolePermission rp = new RolePermission();
        rp.setId(new RolePermission.Id(ROLE, p));
        rolePermissionRepository.save(rp);

        User user = new User();
        user.setBusinessId(TENANT);
        user.setEmail("suprep@test");
        user.setName("Sup Rep User");
        user.setRoleId(ROLE);
        user.setBranchId(branchId);
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(user);

        ItemType type = new ItemType();
        type.setBusinessId(TENANT);
        type.setTypeKey("goods");
        type.setLabel("Goods");
        type.setSortOrder(0);
        itemTypeRepository.save(type);

        itemId = UUID.randomUUID().toString();
        Item item = new Item();
        item.setId(itemId);
        item.setBusinessId(TENANT);
        item.setSku("SKU-SUP-1");
        item.setName("Stock item");
        item.setItemTypeId(type.getId());
        item.setHasExpiry(false);
        itemRepository.save(item);

        Supplier sup = new Supplier();
        sup.setBusinessId(TENANT);
        sup.setName("Test Supplier");
        supplierRepository.save(sup);
        supplierId = sup.getId();

        InventoryBatch batch = new InventoryBatch();
        batch.setId(batchId);
        batch.setBusinessId(TENANT);
        batch.setBranchId(branchId);
        batch.setItemId(itemId);
        batch.setSupplierId(supplierId);
        batch.setBatchNumber("B1");
        batch.setSourceType("test");
        batch.setSourceId(UUID.randomUUID().toString());
        batch.setInitialQuantity(new BigDecimal("10.0000"));
        batch.setQuantityRemaining(new BigDecimal("10.0000"));
        batch.setUnitCost(new BigDecimal("1.0000"));
        batch.setReceivedAt(Instant.parse("2026-01-01T00:00:00Z"));
        batch.setStatus("active");
        inventoryBatchRepository.save(batch);
    }

    @Test
    void rebuild_monthlySpend_matchesPostedInvoicesAndWastage() {
        LocalDate jan = LocalDate.of(2026, 1, 15);
        seedInvoice("inv-1", jan, new BigDecimal("100.00"), new BigDecimal("5.0000"));
        seedInvoice("inv-2", LocalDate.of(2026, 1, 20), new BigDecimal("50.00"), new BigDecimal("2.0000"));
        seedWastageMovement(LocalDate.of(2026, 1, 10), new BigDecimal("0.5000"));

        refresher.refresh(TENANT);

        SupplierMonthlySpendResponse res = supplierReportsService.monthlySpend(
                TENANT, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1));

        assertThat(res.rows()).hasSize(1);
        SupplierMonthlySpendResponse.Row row = res.rows().get(0);
        assertThat(row.supplierId()).isEqualTo(supplierId);
        assertThat(row.spend()).isEqualByComparingTo("150.00");
        assertThat(row.qty()).isEqualByComparingTo("7.0000");
        assertThat(row.invoiceCount()).isEqualTo(2L);
        assertThat(row.wastageQty()).isEqualByComparingTo("0.5000");
        assertThat(res.totalSpend()).isEqualByComparingTo("150.00");
    }

    private void seedInvoice(String invNo, LocalDate invoiceDate, BigDecimal grandTotal, BigDecimal lineQty) {
        SupplierInvoice si = new SupplierInvoice();
        si.setBusinessId(TENANT);
        si.setSupplierId(supplierId);
        si.setInvoiceNumber(invNo);
        si.setInvoiceDate(invoiceDate);
        si.setGrandTotal(grandTotal);
        si.setSubtotal(grandTotal);
        si.setStatus(PurchasingConstants.INVOICE_POSTED);
        supplierInvoiceRepository.save(si);

        SupplierInvoiceLine line = new SupplierInvoiceLine();
        line.setInvoiceId(si.getId());
        line.setDescription("goods");
        line.setItemId(itemId);
        line.setQty(lineQty);
        line.setUnitCost(new BigDecimal("10.0000"));
        line.setLineTotal(grandTotal);
        line.setSortOrder(0);
        supplierInvoiceLineRepository.save(line);
    }

    private void seedWastageMovement(LocalDate day, BigDecimal absQty) {
        StockMovement sm = new StockMovement();
        sm.setBusinessId(TENANT);
        sm.setBranchId(branchId);
        sm.setItemId(itemId);
        sm.setBatchId(batchId);
        sm.setMovementType(PurchasingConstants.MOVEMENT_WASTAGE);
        sm.setReferenceType("test");
        sm.setReferenceId(UUID.randomUUID().toString());
        sm.setQuantityDelta(absQty.negate());
        sm.setCreatedAt(day.atTime(10, 0).toInstant(ZoneOffset.UTC));
        stockMovementRepository.save(sm);
    }

    private static Permission perm(String id, String key, String desc) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(desc);
        return p;
    }
}
