package zelisline.ub.purchasing.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.domain.Category;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.CategoryRepository;
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
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.api.dto.PriceCompetitivenessRow;
import zelisline.ub.purchasing.api.dto.SingleSourceRiskRow;
import zelisline.ub.purchasing.api.dto.SpendBySupplierCategoryRow;
import zelisline.ub.purchasing.domain.SupplierInvoice;
import zelisline.ub.purchasing.domain.SupplierInvoiceLine;
import zelisline.ub.purchasing.repository.SupplierInvoiceLineRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.repository.SupplierContactRepository;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class SupplierIntelligenceIT {

    private static final String TENANT = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String P_INTEL = "11111111-0000-0000-0000-000000000053";
    private static final String ROLE_OWNER = "22222222-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private BusinessRepository businessRepository;
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
    private CategoryRepository categoryRepository;
    @Autowired
    private SupplierRepository supplierRepository;
    @Autowired
    private SupplierContactRepository supplierContactRepository;
    @Autowired
    private SupplierProductRepository supplierProductRepository;
    @Autowired
    private CatalogBootstrapService catalogBootstrapService;
    @Autowired
    private ItemCatalogService itemCatalogService;
    @Autowired
    private SupplierInvoiceRepository supplierInvoiceRepository;
    @Autowired
    private SupplierInvoiceLineRepository supplierInvoiceLineRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User owner;
    private String goodsTypeId;
    private String categoryId;
    private String supplierAId;
    private String supplierBId;
    private LocalDate windowStart;
    private LocalDate windowEnd;

    @BeforeEach
    void seed() {
        supplierInvoiceLineRepository.deleteAll();
        supplierInvoiceRepository.deleteAll();
        supplierProductRepository.deleteAll();
        supplierContactRepository.deleteAll();
        supplierRepository.deleteAll();
        itemRepository.deleteAll();
        categoryRepository.deleteAll();
        itemTypeRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Intel Shop");
        b.setSlug("intel-shop");
        businessRepository.save(b);

        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT);
        goodsTypeId = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT).getFirst().getId();

        permissionRepository.save(perm(P_INTEL, "purchasing.intelligence.read", "intel"));

        Role ownerRole = new Role();
        ownerRole.setId(ROLE_OWNER);
        ownerRole.setBusinessId(null);
        ownerRole.setRoleKey("owner");
        ownerRole.setName("Owner");
        ownerRole.setSystem(true);
        roleRepository.save(ownerRole);
        RolePermission rp = new RolePermission();
        rp.setId(new RolePermission.Id(ROLE_OWNER, P_INTEL));
        rolePermissionRepository.save(rp);

        owner = new User();
        owner.setBusinessId(TENANT);
        owner.setEmail("owner-intel@test");
        owner.setName("Owner");
        owner.setRoleId(ROLE_OWNER);
        owner.setStatus(UserStatus.ACTIVE);
        owner.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(owner);

        Category cat = new Category();
        cat.setBusinessId(TENANT);
        cat.setName("Beverages");
        cat.setSlug("beverages");
        cat.setPosition(0);
        categoryRepository.save(cat);
        categoryId = cat.getId();

        Supplier sa = new Supplier();
        sa.setBusinessId(TENANT);
        sa.setName("Vendor A");
        sa.setSupplierType("distributor");
        sa.setStatus("active");
        supplierRepository.save(sa);
        supplierAId = sa.getId();

        Supplier sb = new Supplier();
        sb.setBusinessId(TENANT);
        sb.setSupplierType("distributor");
        sb.setStatus("active");
        sb.setName("Vendor B");
        supplierRepository.save(sb);
        supplierBId = sb.getId();

        windowStart = LocalDate.of(2026, 3, 1);
        windowEnd = LocalDate.of(2026, 3, 31);

        String spendItemId = itemCatalogService.createItem(
                TENANT,
                new CreateItemRequest(
                        "SKU-SPEND", null, "Cola", null, goodsTypeId, categoryId, null, null,
                        false, true, true,
                        null, null, null, null, null, null, null, null, null, null, null
                ),
                null
        ).body().id();

        postedInvoice(supplierAId, LocalDate.of(2026, 3, 5), new BigDecimal("100.00"),
                line(spendItemId, new BigDecimal("100.00")));
        postedInvoice(supplierBId, LocalDate.of(2026, 3, 10), new BigDecimal("50.00"),
                line(spendItemId, new BigDecimal("50.00")));

        Item priceItem = manualItem("SKU-PRICE", "Sugar");
        SupplierProduct primary = new SupplierProduct();
        primary.setSupplierId(supplierAId);
        primary.setItemId(priceItem.getId());
        primary.setPrimaryLink(true);
        primary.setLastCostPrice(new BigDecimal("10.0000"));
        primary.setActive(true);
        supplierProductRepository.save(primary);

        SupplierProduct secondary = new SupplierProduct();
        secondary.setSupplierId(supplierBId);
        secondary.setItemId(priceItem.getId());
        secondary.setPrimaryLink(false);
        secondary.setActive(true);
        supplierProductRepository.save(secondary);

        postedInvoice(supplierBId, LocalDate.of(2026, 3, 12), new BigDecimal("22.00"),
                line(priceItem.getId(), new BigDecimal("11.0000"), 2, new BigDecimal("22.00")));

        Item soleItem = manualItem("SKU-SOLE", "Only One");
        SupplierProduct sole = new SupplierProduct();
        sole.setSupplierId(supplierBId);
        sole.setItemId(soleItem.getId());
        sole.setPrimaryLink(true);
        sole.setActive(true);
        supplierProductRepository.save(sole);
    }

    @Test
    void spendBySupplierAndCategory() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/purchasing/intelligence/spend-by-supplier-category")
                        .param("from", windowStart.toString())
                        .param("to", windowEnd.toString())
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andReturn();

        List<SpendBySupplierCategoryRow> rows = objectMapper.readValue(
                r.getResponse().getContentAsString(), new TypeReference<>() {
                });
        BigDecimal aSpend = rows.stream()
                .filter(row -> supplierAId.equals(row.supplierId()) && categoryId.equals(row.categoryId()))
                .map(SpendBySupplierCategoryRow::spendTotal)
                .findFirst()
                .orElseThrow();
        BigDecimal bSpend = rows.stream()
                .filter(row -> supplierBId.equals(row.supplierId()) && categoryId.equals(row.categoryId()))
                .map(SpendBySupplierCategoryRow::spendTotal)
                .findFirst()
                .orElseThrow();
        assertThat(aSpend).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(bSpend).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    void priceCompetitivenessVsPrimary() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/purchasing/intelligence/price-competitiveness")
                        .param("from", windowStart.toString())
                        .param("to", windowEnd.toString())
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andReturn();

        List<PriceCompetitivenessRow> rows = objectMapper.readValue(
                r.getResponse().getContentAsString(), new TypeReference<>() {
                });
        List<PriceCompetitivenessRow> sugar = rows.stream()
                .filter(row -> "SKU-PRICE".equals(row.itemSku()))
                .toList();
        assertThat(sugar).hasSize(1);
        PriceCompetitivenessRow row = sugar.get(0);
        assertThat(row.invoicingSupplierId()).isEqualTo(supplierBId);
        assertThat(row.primarySupplierId()).isEqualTo(supplierAId);
        assertThat(row.paidUnitCost()).isEqualByComparingTo(new BigDecimal("11.0000"));
        assertThat(row.variancePercentVsPrimary()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(row.purchasedFromPrimarySupplier()).isFalse();
    }

    @Test
    void singleSourceRiskListsSoleLinkedSellableItems() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/purchasing/intelligence/single-source-risk")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andReturn();

        List<SingleSourceRiskRow> rows = objectMapper.readValue(
                r.getResponse().getContentAsString(), new TypeReference<>() {
                });
        assertThat(rows.stream().map(SingleSourceRiskRow::sku).toList()).contains("SKU-SOLE");
        assertThat(rows.stream().filter(row -> "SKU-SOLE".equals(row.sku())).count()).isEqualTo(1);
        assertThat(rows.stream().filter(row -> "SKU-SOLE".equals(row.sku())).findFirst().orElseThrow().soleSupplierId())
                .isEqualTo(supplierBId);
    }

    @Test
    void invalidDateRangeReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/purchasing/intelligence/spend-by-supplier-category")
                        .param("from", "2026-06-01")
                        .param("to", "2026-03-01")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isBadRequest());
    }

    private Item manualItem(String sku, String name) {
        Item it = new Item();
        it.setBusinessId(TENANT);
        it.setSku(sku);
        it.setName(name);
        it.setItemTypeId(goodsTypeId);
        it.setUnitType("each");
        it.setSellable(true);
        it.setStocked(true);
        itemRepository.save(it);
        return it;
    }

    private void postedInvoice(
            String supplierId,
            LocalDate invoiceDate,
            BigDecimal grandTotal,
            SupplierInvoiceLine line
    ) {
        SupplierInvoice inv = new SupplierInvoice();
        inv.setBusinessId(TENANT);
        inv.setSupplierId(supplierId);
        inv.setInvoiceNumber("INV-" + System.nanoTime());
        inv.setInvoiceDate(invoiceDate);
        inv.setDueDate(invoiceDate.plusDays(30));
        inv.setSubtotal(grandTotal);
        inv.setTaxTotal(BigDecimal.ZERO);
        inv.setGrandTotal(grandTotal);
        inv.setStatus(PurchasingConstants.INVOICE_POSTED);
        supplierInvoiceRepository.save(inv);
        line.setInvoiceId(inv.getId());
        supplierInvoiceLineRepository.save(line);
    }

    private static SupplierInvoiceLine line(String itemId, BigDecimal lineTotal) {
        return line(itemId, lineTotal, 1, lineTotal);
    }

    private static SupplierInvoiceLine line(String itemId, BigDecimal unitCost, int qty, BigDecimal lineTotal) {
        SupplierInvoiceLine sil = new SupplierInvoiceLine();
        sil.setDescription("line");
        sil.setItemId(itemId);
        sil.setQty(BigDecimal.valueOf(qty));
        sil.setUnitCost(unitCost);
        sil.setLineTotal(lineTotal);
        sil.setSortOrder(0);
        return sil;
    }

    private static Permission perm(String id, String key, String desc) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(desc);
        return p;
    }

}
