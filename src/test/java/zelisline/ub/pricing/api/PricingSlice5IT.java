package zelisline.ub.pricing.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import zelisline.ub.pricing.domain.SellingPrice;
import zelisline.ub.pricing.repository.BuyingPriceRepository;
import zelisline.ub.pricing.repository.PriceRuleRepository;
import zelisline.ub.pricing.repository.SellingPriceRepository;
import zelisline.ub.pricing.repository.TaxRateRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PricingSlice5IT {

    private static final String TENANT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String P_READ = "11111111-0000-0000-0000-000000000039";
    private static final String P_PR_R = "11111111-0000-0000-0000-000000000060";
    private static final String P_PR_SELL = "11111111-0000-0000-0000-000000000061";
    private static final String P_PR_COST = "11111111-0000-0000-0000-000000000062";
    private static final String P_PR_RULE = "11111111-0000-0000-0000-000000000063";
    private static final String ROLE_OWNER = "22222222-0000-0000-0000-000000000055";

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
    private CatalogBootstrapService catalogBootstrapService;
    @Autowired
    private ItemCatalogService itemCatalogService;
    @Autowired
    private SupplierRepository supplierRepository;
    @Autowired
    private SellingPriceRepository sellingPriceRepository;
    @Autowired
    private BuyingPriceRepository buyingPriceRepository;
    @Autowired
    private PriceRuleRepository priceRuleRepository;
    @Autowired
    private TaxRateRepository taxRateRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User owner;
    private String itemId;
    private String supplierId;

    @BeforeEach
    void seed() {
        taxRateRepository.deleteAll();
        priceRuleRepository.deleteAll();
        sellingPriceRepository.deleteAll();
        buyingPriceRepository.deleteAll();
        itemRepository.deleteAll();
        itemTypeRepository.deleteAll();
        supplierRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Pricing Shop");
        b.setSlug("pricing-shop");
        b.setSettings("{}");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Main");
        branchRepository.save(br);

        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT);
        String goodsTypeId = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT).getFirst().getId();

        for (Permission p : List.of(
                perm(P_READ, "catalog.items.read", "cr"),
                perm(P_PR_R, "pricing.read", "pr"),
                perm(P_PR_SELL, "pricing.sell_price.set", "ps"),
                perm(P_PR_COST, "pricing.cost_price.set", "pc"),
                perm(P_PR_RULE, "pricing.rules.manage", "pm")
        )) {
            permissionRepository.save(p);
        }

        Role ownerRole = new Role();
        ownerRole.setId(ROLE_OWNER);
        ownerRole.setBusinessId(null);
        ownerRole.setRoleKey("owner");
        ownerRole.setName("Owner");
        ownerRole.setSystem(true);
        roleRepository.save(ownerRole);
        for (String pid : List.of(P_READ, P_PR_R, P_PR_SELL, P_PR_COST, P_PR_RULE)) {
            RolePermission rp = new RolePermission();
            rp.setId(new RolePermission.Id(ROLE_OWNER, pid));
            rolePermissionRepository.save(rp);
        }

        owner = new User();
        owner.setBusinessId(TENANT);
        owner.setEmail("owner-pr@test");
        owner.setName("Owner");
        owner.setRoleId(ROLE_OWNER);
        owner.setStatus(UserStatus.ACTIVE);
        owner.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(owner);

        itemId = itemCatalogService.createItem(
                TENANT,
                new CreateItemRequest(
                        "SKU-PRC", null, "Pricing Item", null, goodsTypeId, null, null, null,
                        false, true, true,
                        null, null, null, null, null, null, null, null, null, false, null
                ),
                null
        ).body().id();

        supplierId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa01";
        Supplier sup = new Supplier();
        sup.setId(supplierId);
        sup.setBusinessId(TENANT);
        sup.setName("Acme");
        sup.setStatus("active");
        supplierRepository.save(sup);
    }

    @Test
    void sellingPrice_closesPreviousOpenRow() throws Exception {
        postSelling(LocalDate.of(2026, 1, 1), new BigDecimal("10.00"));
        postSelling(LocalDate.of(2026, 2, 1), new BigDecimal("11.00"));

        List<SellingPrice> all = sellingPriceRepository.findAll();
        assertThat(all).hasSize(2);
        SellingPrice first = all.stream()
                .filter(sp -> sp.getEffectiveFrom().equals(LocalDate.of(2026, 1, 1)))
                .findFirst()
                .orElseThrow();
        assertThat(first.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 1, 31));
        SellingPrice second = all.stream()
                .filter(sp -> sp.getEffectiveFrom().equals(LocalDate.of(2026, 2, 1)))
                .findFirst()
                .orElseThrow();
        assertThat(second.getEffectiveTo()).isNull();
    }

    @Test
    void sellingPrice_conflictOnSameEffectiveFrom() throws Exception {
        postSelling(LocalDate.of(2026, 3, 1), new BigDecimal("9.00"));
        mockMvc.perform(post("/api/v1/pricing/selling-prices")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"itemId":"%s","price":10.00,"effectiveFrom":"2026-03-01","notes":null}
                                """.formatted(itemId))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isConflict());
    }

    @Test
    void suggestSell_appliesMarginRule() throws Exception {
        postBuying(LocalDate.of(2026, 1, 10), new BigDecimal("8.0000"));
        mockMvc.perform(post("/api/v1/pricing/price-rules")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"Default margin","ruleType":"MARGIN_PERCENT",
                                 "paramsJson":"{\\"marginPercent\\":25}","active":true}
                                """)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated());

        MvcResult res = mockMvc.perform(get("/api/v1/pricing/suggest/sell")
                        .param("itemId", itemId)
                        .param("supplierId", supplierId)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andReturn();

        var body = objectMapper.readTree(res.getResponse().getContentAsString());
        assertThat(body.get("latestUnitCost").decimalValue().setScale(2, RoundingMode.HALF_UP))
                .isEqualByComparingTo("8.00");
        assertThat(body.get("suggestedSellPrice").decimalValue().setScale(2, RoundingMode.HALF_UP))
                .isEqualByComparingTo("10.00");
    }

    @Test
    void listTaxRates_afterCreate() throws Exception {
        mockMvc.perform(post("/api/v1/pricing/tax-rates")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"VAT\",\"ratePercent\":16,\"inclusive\":false,\"active\":true}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated());

        MvcResult res = mockMvc.perform(get("/api/v1/pricing/tax-rates")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andReturn();

        List<?> list = objectMapper.readValue(res.getResponse().getContentAsString(), new TypeReference<>() {});
        assertThat(list).hasSize(1);
    }

    private void postSelling(LocalDate from, BigDecimal price) throws Exception {
        mockMvc.perform(post("/api/v1/pricing/selling-prices")
                        .contentType(APPLICATION_JSON)
                        .content(("{\"itemId\":\"" + itemId + "\",\"price\":" + price.toPlainString()
                                + ",\"effectiveFrom\":\"" + from + "\",\"notes\":null}"))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated());
    }

    private void postBuying(LocalDate from, BigDecimal unitCost) throws Exception {
        mockMvc.perform(post("/api/v1/pricing/buying-prices")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"itemId":"%s","supplierId":"%s","unitCost":%s,"effectiveFrom":"%s",
                                 "sourceType":"manual","notes":null}
                                """.formatted(itemId, supplierId, unitCost.toPlainString(), from))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated());
    }

    private static Permission perm(String id, String key, String desc) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(desc);
        return p;
    }
}
