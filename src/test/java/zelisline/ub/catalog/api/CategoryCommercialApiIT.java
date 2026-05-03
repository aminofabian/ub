package zelisline.ub.catalog.api;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import zelisline.ub.catalog.application.CatalogBootstrapService;
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
import zelisline.ub.pricing.domain.PriceRule;
import zelisline.ub.pricing.domain.TaxRate;
import zelisline.ub.pricing.repository.PriceRuleRepository;
import zelisline.ub.pricing.repository.TaxRateRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class CategoryCommercialApiIT {

    private static final String TENANT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String PERM_READ = "33333333-3333-3333-3333-333333333301";
    private static final String PERM_WRITE = "33333333-3333-3333-3333-333333333302";
    private static final String PERM_CAT_WRITE = "33333333-3333-3333-3333-333333333303";
    private static final String ROLE_OWNER = "44444444-4444-4444-4444-444444444401";
    private static final String RULE_ID = "55555555-5555-5555-5555-555555555501";

    @Autowired
    private MockMvc mockMvc;

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
    private CategoryRepository categoryRepository;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private TaxRateRepository taxRateRepository;

    @Autowired
    private PriceRuleRepository priceRuleRepository;

    @Autowired
    private CatalogBootstrapService catalogBootstrapService;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private User owner;

    @BeforeEach
    void seed() {
        itemRepository.deleteAll();
        categoryRepository.deleteAll();
        priceRuleRepository.deleteAll();
        taxRateRepository.deleteAll();
        itemTypeRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        businessRepository.deleteAll();

        Business business = new Business();
        business.setId(TENANT);
        business.setName("Commercial shop");
        business.setSlug("commercial-shop");
        business.setSettings("{}");
        businessRepository.save(business);
        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT);

        permissionRepository.save(perm(PERM_READ, "catalog.items.read", "Read"));
        permissionRepository.save(perm(PERM_WRITE, "catalog.items.write", "Write items"));
        permissionRepository.save(perm(PERM_CAT_WRITE, "catalog.categories.write", "Write taxonomy"));

        Role ownerRole = new Role();
        ownerRole.setId(ROLE_OWNER);
        ownerRole.setBusinessId(null);
        ownerRole.setRoleKey("owner");
        ownerRole.setName("Owner");
        ownerRole.setSystem(true);
        roleRepository.save(ownerRole);
        grant(ROLE_OWNER, PERM_READ);
        grant(ROLE_OWNER, PERM_WRITE);
        grant(ROLE_OWNER, PERM_CAT_WRITE);

        owner = new User();
        owner.setBusinessId(TENANT);
        owner.setEmail("owner@commercial");
        owner.setName("Owner");
        owner.setRoleId(ROLE_OWNER);
        owner.setStatus(UserStatus.ACTIVE);
        owner.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(owner);
    }

    @Test
    void categoryTreeAndChildrenExposeHierarchy() throws Exception {
        String parentJson = mockMvc.perform(post("/api/v1/categories")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Department\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String parentId = JsonPath.read(parentJson, "$.id");

        mockMvc.perform(post("/api/v1/categories")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Shelf\",\"parentId\":\"" + parentId + "\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/categories/tree")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(parentId))
                .andExpect(jsonPath("$[0].depth").value(0))
                .andExpect(jsonPath("$[0].children", hasSize(1)))
                .andExpect(jsonPath("$[0].children[0].depth").value(1))
                .andExpect(jsonPath("$[0].children[0].name").value("Shelf"));

        mockMvc.perform(get("/api/v1/categories/" + parentId + "/children")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].parentId").value(parentId))
                .andExpect(jsonPath("$[0].name").value("Shelf"));
    }

    @Test
    void itemListIncludeCategoryDescendantsFindsNestedItems() throws Exception {
        String goodsId = goodsTypeId();
        String parentJson = mockMvc.perform(post("/api/v1/categories")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Outer\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String parentId = JsonPath.read(parentJson, "$.id");

        String childJson = mockMvc.perform(post("/api/v1/categories")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Inner\",\"parentId\":\"" + parentId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String childId = JsonPath.read(childJson, "$.id");

        mockMvc.perform(post("/api/v1/items")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content(
                                "{\"sku\":\"SKU-DESC\",\"name\":\"Nested SKU\",\"itemTypeId\":\""
                                        + goodsId
                                        + "\",\"categoryId\":\""
                                        + childId
                                        + "\",\"isSellable\":true,\"isStocked\":true}"
                        ))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/items")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .param("categoryId", parentId)
                        .param("includeCategoryDescendants", "true")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("SKU-DESC"));

        mockMvc.perform(get("/api/v1/items")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .param("categoryId", parentId)
                        .param("includeCategoryDescendants", "false")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void pricingContextInheritsMarkupTaxAndLinkedRules() throws Exception {
        TaxRate tax = new TaxRate();
        tax.setId("66666666-6666-6666-6666-666666666601");
        tax.setBusinessId(TENANT);
        tax.setName("GST");
        tax.setRatePercent(new BigDecimal("10.000"));
        tax.setInclusive(false);
        tax.setActive(true);
        tax.setCreatedAt(Instant.now());
        tax.setUpdatedAt(Instant.now());
        taxRateRepository.save(tax);

        PriceRule rule = new PriceRule();
        rule.setId(RULE_ID);
        rule.setBusinessId(TENANT);
        rule.setName("Margin rule");
        rule.setRuleType("MARGIN_PERCENT");
        rule.setParamsJson("{\"marginPercent\":15}");
        rule.setActive(true);
        rule.setCreatedAt(Instant.now());
        rule.setUpdatedAt(Instant.now());
        priceRuleRepository.save(rule);

        String goodsId = goodsTypeId();
        String parentJson = mockMvc.perform(post("/api/v1/categories")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"ParentCat\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String parentId = JsonPath.read(parentJson, "$.id");

        mockMvc.perform(patch("/api/v1/categories/" + parentId)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content(
                                "{\"defaultMarkupPct\":22.5,\"defaultTaxRateId\":\""
                                        + tax.getId()
                                        + "\"}"
                        ))
                .andExpect(status().isOk());

        String leafJson = mockMvc.perform(post("/api/v1/categories")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"LeafCat\",\"parentId\":\"" + parentId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String leafId = JsonPath.read(leafJson, "$.id");

        mockMvc.perform(post("/api/v1/categories/" + leafId + "/price-rules")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("{\"ruleId\":\"" + RULE_ID + "\",\"precedence\":0}"))
                .andExpect(status().isCreated());

        String itemJson = mockMvc.perform(post("/api/v1/items")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content(
                                "{\"sku\":\"SKU-PC\",\"name\":\"Priced\",\"itemTypeId\":\""
                                        + goodsId
                                        + "\",\"categoryId\":\""
                                        + leafId
                                        + "\",\"isSellable\":true,\"isStocked\":true}"
                        ))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String itemId = JsonPath.read(itemJson, "$.id");

        mockMvc.perform(get("/api/v1/items/" + itemId + "/pricing-context")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemId").value(itemId))
                .andExpect(jsonPath("$.categoryId").value(leafId))
                .andExpect(jsonPath("$.inheritedMarkupPercent").value(22.5))
                .andExpect(jsonPath("$.markupSourceKey").value("category:" + parentId))
                .andExpect(jsonPath("$.resolvedTaxRateId").value(tax.getId()))
                .andExpect(jsonPath("$.resolvedTaxRatePercent").value(10))
                .andExpect(jsonPath("$.taxSourceKey").value("category:" + parentId))
                .andExpect(jsonPath("$.linkedPriceRules", hasSize(1)))
                .andExpect(jsonPath("$.linkedPriceRules[0].id").value(RULE_ID))
                .andExpect(jsonPath("$.linkedPriceRules[0].precedence").value(0));

        mockMvc.perform(get("/api/v1/categories/" + leafId + "/price-rules")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].ruleId").value(RULE_ID));
    }

    private String goodsTypeId() {
        return itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT).stream()
                .filter(t -> "goods".equals(t.getTypeKey()))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private static Permission perm(String id, String key, String desc) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(desc);
        return p;
    }

    private void grant(String roleId, String permissionId) {
        RolePermission rp = new RolePermission();
        rp.setId(new RolePermission.Id(roleId, permissionId));
        rolePermissionRepository.save(rp);
    }
}
