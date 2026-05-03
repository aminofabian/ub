package zelisline.ub.storefront.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.domain.Category;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.pricing.domain.SellingPrice;
import zelisline.ub.pricing.repository.SellingPriceRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PublicStorefrontCatalogIT {

    private static final String TENANT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String SLUG = "public-shop-it";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private ItemTypeRepository itemTypeRepository;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private SellingPriceRepository sellingPriceRepository;

    @Autowired
    private CatalogBootstrapService catalogBootstrapService;

    @Autowired
    private ItemCatalogService itemCatalogService;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private String branchId;
    private String publishedItemId;
    private String hiddenItemId;

    @BeforeEach
    void seed() {
        sellingPriceRepository.deleteAll();
        itemRepository.deleteAll();
        categoryRepository.deleteAll();
        itemTypeRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Public Shop");
        b.setSlug(SLUG);
        b.setSettings("{}");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Catalog Branch");
        br.setActive(true);
        branchId = branchRepository.save(br).getId();

        b.setSettings(
                "{\"storefront\":{\"enabled\":true,\"catalogBranchId\":\"%s\",\"label\":\"Hi\",\"announcement\":\"Sale\"}}"
                        .formatted(branchId));
        businessRepository.save(b);

        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT);
        String goodsTypeId = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT).getFirst().getId();

        Category category = new Category();
        category.setBusinessId(TENANT);
        category.setName("Beverages");
        category.setSlug("beverages");
        category.setPosition(0);
        String categoryId = categoryRepository.save(category).getId();

        publishedItemId = itemCatalogService.createItem(
                TENANT,
                new CreateItemRequest(
                        "SKU-PUB", null, "Published Item", null, goodsTypeId, categoryId, null, null,
                        false, true, true,
                        null, null, null, null, null, null, null, null, null, false, null
                ),
                null
        ).body().id();

        patchWebPublished(publishedItemId, true);

        hiddenItemId = itemCatalogService.createItem(
                TENANT,
                new CreateItemRequest(
                        "SKU-HID", null, "Hidden Item", null, goodsTypeId, null, null, null,
                        false, true, true,
                        null, null, null, null, null, null, null, null, null, false, null
                ),
                null
        ).body().id();

        patchWebPublished(hiddenItemId, false);

        SellingPrice sp = new SellingPrice();
        sp.setBusinessId(TENANT);
        sp.setItemId(publishedItemId);
        sp.setBranchId(branchId);
        sp.setPrice(new BigDecimal("99.50"));
        sp.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        sellingPriceRepository.save(sp);
    }

    private void patchWebPublished(String itemId, boolean on) {
        Item row = itemRepository.findById(itemId).orElseThrow();
        row.setWebPublished(on);
        itemRepository.save(row);
    }

    @Test
    void catalog_categories_includesPublishedStockedCategories() throws Exception {
        mockMvc.perform(get("/api/v1/public/businesses/" + SLUG + "/catalog/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories.length()").value(1))
                .andExpect(jsonPath("$.categories[0].name").value("Beverages"))
                .andExpect(jsonPath("$.categories[0].slug").value("beverages"));
    }

    @Test
    void storefront_returnsBranchSummaryWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/v1/public/businesses/" + SLUG + "/storefront"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(SLUG))
                .andExpect(jsonPath("$.catalogBranchId").value(branchId))
                .andExpect(jsonPath("$.catalogBranchName").value("Catalog Branch"))
                .andExpect(jsonPath("$.currency").value("KES"))
                .andExpect(jsonPath("$.label").value("Hi"));
    }

    @Test
    void catalog_listsOnlyPublishedItemsWithPrice() throws Exception {
        mockMvc.perform(get("/api/v1/public/businesses/" + SLUG + "/catalog/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(publishedItemId))
                .andExpect(jsonPath("$.items[0].price").value(99.5));
    }

    @Test
    void itemDetail_returnsPublishedItem() throws Exception {
        mockMvc.perform(get("/api/v1/public/businesses/" + SLUG + "/catalog/items/" + publishedItemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Published Item"))
                .andExpect(jsonPath("$.price").value(99.5));
    }

    @Test
    void itemDetail_returns404ForUnpublished() throws Exception {
        mockMvc.perform(get("/api/v1/public/businesses/" + SLUG + "/catalog/items/" + hiddenItemId))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownSlug_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/public/businesses/no-such-shop/catalog/items"))
                .andExpect(status().isNotFound());
    }

    @Test
    void disabledStorefront_returns404() throws Exception {
        Business b = businessRepository.findById(TENANT).orElseThrow();
        b.setSettings("{\"storefront\":{\"enabled\":false}}");
        businessRepository.save(b);

        mockMvc.perform(get("/api/v1/public/businesses/" + SLUG + "/catalog/items"))
                .andExpect(status().isNotFound());
    }
}
