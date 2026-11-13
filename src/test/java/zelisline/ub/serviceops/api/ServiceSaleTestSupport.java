package zelisline.ub.serviceops.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.application.LedgerBootstrapService;
import zelisline.ub.finance.domain.JournalLine;
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
import zelisline.ub.payroll.repository.StaffProfileRepository;
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.purchasing.repository.StockMovementRepository;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.sales.repository.SalePaymentRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.serviceops.repository.ServiceCategoryRepository;
import zelisline.ub.serviceops.repository.ServiceOfferingRepository;
import zelisline.ub.serviceops.repository.ServiceStaffLinkRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/** Shared set-up for the service sale tests: a kinyozi with one barber, one service, one product and an open till. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
abstract class ServiceSaleTestSupport {

    protected static final String TENANT = "5a1e0000-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    protected static final String OTHER = "5a1e0000-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    protected static final String ROLE_OWNER = "5a1e2222-2222-4222-8222-222222222201";
    protected static final String ROLE_CASHIER = "5a1e2222-2222-4222-8222-222222222202";

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected BusinessRepository businessRepository;
    @Autowired
    protected BranchRepository branchRepository;
    @Autowired
    protected UserRepository userRepository;
    @Autowired
    protected PermissionRepository permissionRepository;
    @Autowired
    protected RoleRepository roleRepository;
    @Autowired
    protected RolePermissionRepository rolePermissionRepository;
    @Autowired
    protected ItemTypeRepository itemTypeRepository;
    @Autowired
    protected ItemRepository itemRepository;
    @Autowired
    protected CatalogBootstrapService catalogBootstrapService;
    @Autowired
    protected ItemCatalogService itemCatalogService;
    @Autowired
    protected InventoryBatchRepository inventoryBatchRepository;
    @Autowired
    protected StockMovementRepository stockMovementRepository;
    @Autowired
    protected ShiftRepository shiftRepository;
    @Autowired
    protected SaleRepository saleRepository;
    @Autowired
    protected SaleItemRepository saleItemRepository;
    @Autowired
    protected SalePaymentRepository salePaymentRepository;
    @Autowired
    protected JournalLineRepository journalLineRepository;
    @Autowired
    protected JournalEntryRepository journalEntryRepository;
    @Autowired
    protected LedgerAccountRepository ledgerAccountRepository;
    @Autowired
    protected LedgerBootstrapService ledgerBootstrapService;
    @Autowired
    protected StaffProfileRepository staffProfileRepository;
    @Autowired
    protected ServiceStaffLinkRepository staffLinkRepository;
    @Autowired
    protected ServiceOfferingRepository serviceRepository;
    @Autowired
    protected ServiceCategoryRepository categoryRepository;
    @Autowired
    protected zelisline.ub.serviceops.repository.StaffLedgerEntryRepository staffLedgerEntryRepository;
    @Autowired
    protected zelisline.ub.posdraft.repository.PosDraftRepository posDraftRepository;
    @Autowired
    protected zelisline.ub.posdraft.repository.PosDraftLineRepository posDraftLineRepository;
    @Autowired
    protected zelisline.ub.credits.repository.CustomerRepository customerRepository;
    @Autowired
    protected zelisline.ub.credits.repository.CustomerPhoneRepository customerPhoneRepository;
    @Autowired
    protected zelisline.ub.credits.repository.CreditAccountRepository creditAccountRepository;
    @Autowired
    protected zelisline.ub.credits.repository.CreditTransactionRepository creditTransactionRepository;
    @Autowired
    protected zelisline.ub.credits.repository.WalletTransactionRepository walletTransactionRepository;
    @Autowired
    protected zelisline.ub.credits.repository.LoyaltyTransactionRepository loyaltyTransactionRepository;
    @Autowired
    protected zelisline.ub.sales.repository.RefundPaymentRepository refundPaymentRepository;
    @Autowired
    protected zelisline.ub.sales.repository.RefundLineRepository refundLineRepository;
    @Autowired
    protected zelisline.ub.sales.repository.RefundRepository refundRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    protected DomainMappingRepository domainMappingRepository;

    protected User owner;
    protected User cashier;
    protected User otherOwner;
    protected String branchId;
    protected String itemId;
    protected String haircut;
    protected String john;

    @BeforeEach
    void seed() throws Exception {
        posDraftLineRepository.deleteAll();
        posDraftRepository.deleteAll();
        staffLedgerEntryRepository.deleteAll();
        refundPaymentRepository.deleteAll();
        refundLineRepository.deleteAll();
        refundRepository.deleteAll();
        loyaltyTransactionRepository.deleteAll();
        walletTransactionRepository.deleteAll();
        creditTransactionRepository.deleteAll();
        salePaymentRepository.deleteAll();
        saleItemRepository.deleteAll();
        saleRepository.deleteAll();
        creditAccountRepository.deleteAll();
        customerPhoneRepository.deleteAll();
        customerRepository.deleteAll();
        journalLineRepository.deleteAll();
        journalEntryRepository.deleteAll();
        ledgerAccountRepository.deleteAll();
        stockMovementRepository.deleteAll();
        shiftRepository.deleteAll();
        inventoryBatchRepository.deleteAll();
        itemRepository.deleteAll();
        itemTypeRepository.deleteAll();
        staffLinkRepository.deleteAll();
        serviceRepository.deleteAll();
        categoryRepository.deleteAll();
        staffProfileRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        business(TENANT, "kinyozi-sale");
        business(OTHER, "other-sale");
        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Main");
        branchRepository.save(br);
        branchId = br.getId();

        role(ROLE_OWNER, "owner");
        // Not the "cashier" role key: that one is branch-locked and needs a branch on the token.
        role(ROLE_CASHIER, "pos_service_test");
        int n = 0;
        for (String key : List.of("shifts.open", "shifts.close", "shifts.read", "sales.sell", "sales.void.own",
                "sales.void.any", "sales.refund.create", "pricing.sell_price.set", "services.catalog.manage",
                "staff.hr.update", "staff.profile.read", "commissions.plans.manage", "commissions.read",
                "commissions.read_own", "commissions.adjust", "commissions.payout")) {
            String id = "5a1e1111-1111-4111-8111-1111111111" + String.format("%02d", n++);
            permissionRepository.save(perm(id, key));
            grant(ROLE_OWNER, id);
            boolean ownerOnly = key.equals("pricing.sell_price.set") || key.equals("services.catalog.manage")
                    || key.equals("staff.hr.update")
                    || (key.startsWith("commissions.") && !key.equals("commissions.read_own"));
            if (!ownerOnly) {
                grant(ROLE_CASHIER, id);
            }
        }
        owner = user(TENANT, "owner@svc-sale.test", "Owner", ROLE_OWNER);
        cashier = user(TENANT, "cashier@svc-sale.test", "Cashier", ROLE_CASHIER);
        otherOwner = user(OTHER, "owner@other-sale.test", "Other", ROLE_OWNER);

        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT);
        String goodsTypeId = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT).getFirst().getId();
        itemId = itemCatalogService.createItem(
                TENANT,
                new CreateItemRequest(
                        "SKU-OIL", null, "Hair oil", null, goodsTypeId, null, null, null,
                        false, true, true,
                        null, null, null, null, null, null, null, null, null, null, true, null, null, null, null),
                null
        ).body().id();
        InventoryBatch batch = new InventoryBatch();
        batch.setId(UUID.randomUUID().toString());
        batch.setBusinessId(TENANT);
        batch.setBranchId(branchId);
        batch.setItemId(itemId);
        batch.setBatchNumber("BN-OIL");
        batch.setSourceType("test");
        batch.setSourceId(UUID.randomUUID().toString());
        batch.setInitialQuantity(new BigDecimal("10"));
        batch.setQuantityRemaining(new BigDecimal("10"));
        batch.setUnitCost(new BigDecimal("2.00"));
        batch.setReceivedAt(Instant.parse("2026-02-10T12:00:00Z"));
        batch.setExpiryDate(LocalDate.of(2030, 4, 1));
        batch.setStatus("active");
        inventoryBatchRepository.save(batch);
        var item = itemRepository.findById(itemId).orElseThrow();
        item.setCurrentStock(new BigDecimal("10"));
        itemRepository.save(item);

        ledgerBootstrapService.ensureStandardAccounts(TENANT);

        haircut = createService(TENANT, owner, "{\"name\":\"Haircut\",\"durationMinutes\":30,\"price\":300}");
        john = createStaff(TENANT, owner, "John");
        openShift();
    }

    // ------------------------------------------------------------------ helpers

    protected static String serviceLine(String serviceId, String performer, int qty, String price) {
        return "{\"kind\":\"SERVICE\",\"serviceId\":\"" + serviceId + "\",\"performerStaffProfileId\":\"" + performer
                + "\",\"quantity\":" + qty + ",\"unitPrice\":" + price + "}";
    }

    protected String saleBody(String lines, String cash) {
        return "{\"branchId\":\"" + branchId + "\",\"lines\":[" + lines + "],\"payments\":[{\"method\":\"cash\",\"amount\":"
                + cash + "}]}";
    }

    protected JsonNode postSale(String key, String lines, String cash, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/sales"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", key)
                        .content(saleBody(lines, cash)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected void openShift() throws Exception {
        mockMvc.perform(as(post("/api/v1/shifts/open"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"branchId\":\"" + branchId + "\",\"openingCash\":100}"))
                .andExpect(status().isCreated());
    }

    protected String createService(String tenant, User user, String json) throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/services"), tenant, user, ROLE_OWNER).content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    protected String createStaff(String tenant, User user, String name) throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/staff/profiles"), tenant, user, ROLE_OWNER)
                        .content("{\"displayName\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    protected BigDecimal credit(List<JournalLine> lines, String code) {
        String accountId = ledgerAccountRepository.findByBusinessIdAndCode(TENANT, code).orElseThrow().getId();
        return lines.stream().filter(l -> accountId.equals(l.getLedgerAccountId()))
                .map(JournalLine::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    protected BigDecimal debit(List<JournalLine> lines, String code) {
        String accountId = ledgerAccountRepository.findByBusinessIdAndCode(TENANT, code).orElseThrow().getId();
        return lines.stream().filter(l -> accountId.equals(l.getLedgerAccountId()))
                .map(JournalLine::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    protected static void assertBalanced(List<JournalLine> lines) {
        BigDecimal dr = lines.stream().map(JournalLine::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cr = lines.stream().map(JournalLine::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(dr).isEqualByComparingTo(cr);
        assertThat(dr.signum()).isPositive();
    }

    protected static MockHttpServletRequestBuilder as(
            MockHttpServletRequestBuilder builder, String tenant, User user, String roleId) {
        return builder
                .header("X-Tenant-Id", tenant)
                .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                .header(TestAuthenticationFilter.HEADER_ROLE_ID, roleId)
                .contentType(APPLICATION_JSON);
    }

    protected void business(String id, String slug) {
        Business business = new Business();
        business.setId(id);
        business.setName(slug);
        business.setSlug(slug);
        business.setSettings("{}");
        businessRepository.save(business);
    }

    protected void role(String id, String key) {
        Role role = new Role();
        role.setId(id);
        role.setBusinessId(null);
        role.setRoleKey(key);
        role.setName(key);
        role.setSystem(true);
        roleRepository.save(role);
    }

    protected User user(String tenant, String email, String name, String roleId) {
        User user = new User();
        user.setBusinessId(tenant);
        user.setEmail(email);
        user.setName(name);
        user.setRoleId(roleId);
        user.setBranchId(tenant.equals(TENANT) ? branchId : null);
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        return userRepository.save(user);
    }

    protected static Permission perm(String id, String key) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(key);
        return p;
    }

    protected void grant(String roleId, String permissionId) {
        RolePermission rp = new RolePermission();
        rp.setId(new RolePermission.Id(roleId, permissionId));
        rolePermissionRepository.save(rp);
    }
}
