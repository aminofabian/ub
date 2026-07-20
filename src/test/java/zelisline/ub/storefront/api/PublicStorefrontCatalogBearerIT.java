package zelisline.ub.storefront.api;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.domain.Category;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.pricing.domain.SellingPrice;
import zelisline.ub.pricing.repository.SellingPriceRepository;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Storefront catalog must stay public when the BFF forwards a dashboard
 * {@code Authorization} header without {@code X-Tenant-Id} (infinite scroll).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PublicStorefrontCatalogBearerIT {

    private static final String TENANT = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String ROLE_ID = "22222222-2222-2222-2222-222222222201";
    private static final String SLUG = "catalog-bearer-it";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ItemTypeRepository itemTypeRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private SellingPriceRepository sellingPriceRepository;

    @Autowired
    private InventoryBatchRepository inventoryBatchRepository;

    @Autowired
    private CatalogBootstrapService catalogBootstrapService;

    @Autowired
    private ItemCatalogService itemCatalogService;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private String branchId;
    private String firstItemId;
    private String secondItemId;

    @BeforeEach
    void seed() throws Exception {
        sellingPriceRepository.deleteAll();
        inventoryBatchRepository.deleteAll();
        itemRepository.deleteAll();
        categoryRepository.deleteAll();
        itemTypeRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business business = new Business();
        business.setId(TENANT);
        business.setName("Bearer Catalog Shop");
        business.setSlug(SLUG);
        business.setSettings("{}");
        businessRepository.save(business);

        Branch branch = new Branch();
        branch.setBusinessId(TENANT);
        branch.setName("Main");
        branch.setActive(true);
        branchId = branchRepository.save(branch).getId();

        business.setSettings(
                "{\"storefront\":{\"enabled\":true,\"catalogBranchId\":\"%s\"}}"
                        .formatted(branchId));
        businessRepository.save(business);

        Role owner = new Role();
        owner.setId(ROLE_ID);
        owner.setBusinessId(null);
        owner.setRoleKey("owner");
        owner.setName("Owner");
        owner.setSystem(true);
        roleRepository.save(owner);

        User user = new User();
        user.setBusinessId(TENANT);
        user.setEmail("owner@example.com");
        user.setName("Owner");
        user.setRoleId(ROLE_ID);
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash(passwordEncoder.encode("correct-password"));
        userRepository.save(user);

        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT);
        String goodsTypeId = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT).getFirst().getId();

        Category category = new Category();
        category.setBusinessId(TENANT);
        category.setName("Goods");
        category.setSlug("goods");
        category.setPosition(0);
        String categoryId = categoryRepository.save(category).getId();

        firstItemId = createPublishedItem("SKU-A", "Alpha", goodsTypeId, categoryId);
        secondItemId = createPublishedItem("SKU-B", "Beta", goodsTypeId, categoryId);
    }

    @Test
    void catalogCursorPage_withDashboardBearer_withoutTenantHeader_succeeds() throws Exception {
        String token = loginToken();

        MvcResult firstPage = mockMvc.perform(
                        get("/api/v1/public/businesses/" + SLUG + "/catalog/items")
                                .param("limit", "1")
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andReturn();

        String cursor = JsonPath.read(firstPage.getResponse().getContentAsString(), "$.nextCursor");
        org.assertj.core.api.Assertions.assertThat(cursor).isEqualTo(firstItemId);

        mockMvc.perform(
                        get("/api/v1/public/businesses/" + SLUG + "/catalog/items")
                                .param("limit", "1")
                                .param("cursor", cursor)
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(secondItemId));
    }

    private String createPublishedItem(
            String sku,
            String name,
            String goodsTypeId,
            String categoryId
    ) {
        String itemId = itemCatalogService.createItem(
                TENANT,
                new CreateItemRequest(
                        sku, null, name, null, goodsTypeId, categoryId, null, null,
                        false, true, true,
                        null, null, null, null, null, null, null, null, null, null, false, null, null, null, null),
                null
        ).body().id();

        Item row = itemRepository.findById(itemId).orElseThrow();
        row.setWebPublished(true);
        itemRepository.save(row);

        SellingPrice sp = new SellingPrice();
        sp.setBusinessId(TENANT);
        sp.setItemId(itemId);
        sp.setBranchId(branchId);
        sp.setPrice(new BigDecimal("10.00"));
        sp.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        sellingPriceRepository.save(sp);

        InventoryBatch batch = new InventoryBatch();
        batch.setBusinessId(TENANT);
        batch.setBranchId(branchId);
        batch.setItemId(itemId);
        batch.setBatchNumber("B-" + sku);
        batch.setSourceType("test");
        batch.setSourceId(java.util.UUID.randomUUID().toString());
        BigDecimal stockQty = new BigDecimal("5");
        batch.setInitialQuantity(stockQty);
        batch.setQuantityRemaining(stockQty);
        batch.setUnitCost(new BigDecimal("1.0000"));
        batch.setReceivedAt(Instant.parse("2026-01-01T12:00:00Z"));
        batch.setStatus("active");
        inventoryBatchRepository.save(batch);

        return itemId;
    }

    private String loginToken() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"owner@example.com","password":"correct-password"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
    }
}
