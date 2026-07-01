package zelisline.ub.storefront.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.api.dto.CreateVariantRequest;
import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.domain.Category;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.pricing.domain.SellingPrice;
import zelisline.ub.pricing.repository.SellingPriceRepository;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
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
    private InventoryBatchRepository inventoryBatchRepository;

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
        inventoryBatchRepository.deleteAll();
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
                        null, null, null, null, null, null, null, null, null, null, false, null, null, null),
                null
        ).body().id();

        patchWebPublished(publishedItemId, true);

        hiddenItemId = itemCatalogService.createItem(
                TENANT,
                new CreateItemRequest(
                        "SKU-HID", null, "Hidden Item", null, goodsTypeId, null, null, null,
                        false, true, true,
                        null, null, null, null, null, null, null, null, null, null, false, null, null, null),
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

        InventoryBatch batch = new InventoryBatch();
        batch.setBusinessId(TENANT);
        batch.setBranchId(branchId);
        batch.setItemId(publishedItemId);
        batch.setBatchNumber("IT-SEED-1");
        batch.setSourceType("test");
        batch.setSourceId(UUID.randomUUID().toString());
        BigDecimal stockQty = new BigDecimal("10");
        batch.setInitialQuantity(stockQty);
        batch.setQuantityRemaining(stockQty);
        batch.setUnitCost(new BigDecimal("1.0000"));
        batch.setReceivedAt(Instant.parse("2026-01-01T12:00:00Z"));
        batch.setStatus("active");
        inventoryBatchRepository.save(batch);
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
    void catalog_types_listsPublishedStockedTypes() throws Exception {
        mockMvc.perform(get("/api/v1/public/businesses/" + SLUG + "/catalog/types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.types.length()").value(1))
                .andExpect(jsonPath("$.types[0].itemCount").value(1));
    }

    @Test
    void storefront_includesPublishedTypes() throws Exception {
        mockMvc.perform(get("/api/v1/public/businesses/" + SLUG + "/storefront"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.types.length()").value(1))
                .andExpect(jsonPath("$.types[0].itemCount").value(1));
    }

    @Test
    void paymentDisplayInstructions_returnsOkWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/v1/public/businesses/" + SLUG + "/payments/display-instructions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void checkoutPaymentOptions_returnsManualAndOnlineArrays() throws Exception {
        mockMvc.perform(get("/api/v1/public/businesses/" + SLUG + "/payments/checkout-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manual").isArray())
                .andExpect(jsonPath("$.online").isArray());
    }

    @Test
    void catalog_listsOnlyPublishedItemsWithPrice() throws Exception {
        mockMvc.perform(get("/api/v1/public/businesses/" + SLUG + "/catalog/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(publishedItemId))
                .andExpect(jsonPath("$.items[0].price").value(99.5))
                .andExpect(jsonPath("$.items[0].qtyOnHand").value(10));
    }

    @Test
    void itemDetail_returnsPublishedItem() throws Exception {
        mockMvc.perform(get("/api/v1/public/businesses/" + SLUG + "/catalog/items/" + publishedItemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Published Item"))
                .andExpect(jsonPath("$.price").value(99.5))
                .andExpect(jsonPath("$.qtyOnHand").value(10));
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

    @Test
    void itemDetail_resolvesBusinessWideSellingPriceWhenBranchPriceMissing() throws Exception {
        String goodsTypeId = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT).getFirst().getId();
        String categoryId =
                categoryRepository.findByBusinessIdOrderByPositionAsc(TENANT).getFirst().getId();

        String wideOnlyId = itemCatalogService
                .createItem(
                        TENANT,
                        new CreateItemRequest(
                                "SKU-WIDE",
                                null,
                                "Wide Price Item",
                                null,
                                goodsTypeId,
                                categoryId,
                                null,
                                null,
                                false,
                                true,
                                true,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                false,
                                null, null, null),
                        null)
                .body()
                .id();

        patchWebPublished(wideOnlyId, true);

        SellingPrice wide = new SellingPrice();
        wide.setBusinessId(TENANT);
        wide.setItemId(wideOnlyId);
        wide.setBranchId(null);
        wide.setPrice(new BigDecimal("42.00"));
        wide.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        sellingPriceRepository.save(wide);

        InventoryBatch wideBatch = new InventoryBatch();
        wideBatch.setBusinessId(TENANT);
        wideBatch.setBranchId(branchId);
        wideBatch.setItemId(wideOnlyId);
        wideBatch.setBatchNumber("IT-WIDE-1");
        wideBatch.setSourceType("test");
        wideBatch.setSourceId(UUID.randomUUID().toString());
        BigDecimal wideQty = new BigDecimal("3");
        wideBatch.setInitialQuantity(wideQty);
        wideBatch.setQuantityRemaining(wideQty);
        wideBatch.setUnitCost(new BigDecimal("1.0000"));
        wideBatch.setReceivedAt(Instant.parse("2026-01-02T12:00:00Z"));
        wideBatch.setStatus("active");
        inventoryBatchRepository.save(wideBatch);

        mockMvc.perform(get("/api/v1/public/businesses/" + SLUG + "/catalog/items/" + wideOnlyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(42.0))
                .andExpect(jsonPath("$.qtyOnHand").value(3));
    }

    @Test
    void catalog_listsPackageVariantUsingParentStock() throws Exception {
        String goodsTypeId = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT).getFirst().getId();
        String categoryId =
                categoryRepository.findByBusinessIdOrderByPositionAsc(TENANT).getFirst().getId();

        String parentId = itemCatalogService
                .createItem(
                        TENANT,
                        new CreateItemRequest(
                                "SKU-PKG-PARENT",
                                null,
                                "Tray Parent",
                                null,
                                goodsTypeId,
                                categoryId,
                                null,
                                null,
                                false,
                                true,
                                true,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                false,
                                null,
                                null,
                                null),
                        null)
                .body()
                .id();

        patchWebPublished(parentId, true);

        String packageId = itemCatalogService
                .createVariant(
                        TENANT,
                        parentId,
                        new CreateVariantRequest(
                                "SKU-PKG-TRAY",
                                "30-pack tray",
                                null,
                                null,
                                null,
                                categoryId,
                                null,
                                null,
                                null,
                                true,
                                null,
                                true,
                                "tray",
                                new BigDecimal("30"),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null))
                .id();

        patchWebPublished(packageId, true);

        SellingPrice parentPrice = new SellingPrice();
        parentPrice.setBusinessId(TENANT);
        parentPrice.setItemId(parentId);
        parentPrice.setBranchId(branchId);
        parentPrice.setPrice(new BigDecimal("10.00"));
        parentPrice.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        sellingPriceRepository.save(parentPrice);

        SellingPrice packagePrice = new SellingPrice();
        packagePrice.setBusinessId(TENANT);
        packagePrice.setItemId(packageId);
        packagePrice.setBranchId(branchId);
        packagePrice.setPrice(new BigDecimal("250.00"));
        packagePrice.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        sellingPriceRepository.save(packagePrice);

        InventoryBatch parentBatch = new InventoryBatch();
        parentBatch.setBusinessId(TENANT);
        parentBatch.setBranchId(branchId);
        parentBatch.setItemId(parentId);
        parentBatch.setBatchNumber("IT-PKG-PARENT");
        parentBatch.setSourceType("test");
        parentBatch.setSourceId(UUID.randomUUID().toString());
        BigDecimal baseQty = new BigDecimal("90");
        parentBatch.setInitialQuantity(baseQty);
        parentBatch.setQuantityRemaining(baseQty);
        parentBatch.setUnitCost(new BigDecimal("1.0000"));
        parentBatch.setReceivedAt(Instant.parse("2026-01-02T12:00:00Z"));
        parentBatch.setStatus("active");
        inventoryBatchRepository.save(parentBatch);

        mockMvc.perform(get("/api/v1/public/businesses/" + SLUG + "/catalog/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id=='" + packageId + "')].qtyOnHand").value(3))
                .andExpect(jsonPath("$.items[?(@.id=='" + packageId + "')].price").value(250.0));

        mockMvc.perform(get("/api/v1/public/businesses/" + SLUG + "/catalog/items/" + packageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qtyOnHand").value(3))
                .andExpect(jsonPath("$.variantName").value("30-pack tray"));
    }
}
