package zelisline.ub.marketplace.application;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import zelisline.ub.catalog.api.dto.CreateItemPackOptionRequest;
import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.application.ItemPackOptionService;
import zelisline.ub.catalog.repository.ItemPackOptionRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.domain.SupplierProductPackOffer;
import zelisline.ub.suppliers.repository.SupplierProductPackOfferRepository;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Public stall payload (GET /api/v1/public/marketplace/s/{slug}):
 * pack options merged from item defaults + per-link offer overrides.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PublicMarketplacePackOptionsIT {

    private static final String TENANT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private ItemTypeRepository itemTypeRepository;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private ItemPackOptionRepository itemPackOptionRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private SupplierProductRepository supplierProductRepository;

    @Autowired
    private SupplierProductPackOfferRepository supplierProductPackOfferRepository;

    @Autowired
    private CatalogBootstrapService catalogBootstrapService;

    @Autowired
    private ItemCatalogService itemCatalogService;

    @Autowired
    private ItemPackOptionService itemPackOptionService;

    @Autowired
    private MarketplaceSlugService marketplaceSlugService;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private String mandaziItemId;
    private String option12Id;
    private String option48Id;

    @BeforeEach
    void seed() {
        supplierProductPackOfferRepository.deleteAll();
        supplierProductRepository.deleteAll();
        supplierRepository.deleteAll();
        itemPackOptionRepository.deleteAll();
        itemRepository.deleteAll();
        businessRepository.deleteAll();

        insertBusiness(TENANT_A, "shop-a");
        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT_A);

        mandaziItemId = itemCatalogService
                .createItem(TENANT_A, minimalItem("MNDZ-001", "Mandazi", goodsTypeId()), null)
                .body()
                .id();
        option12Id = itemPackOptionService
                .createPackOption(TENANT_A, mandaziItemId,
                        new CreateItemPackOptionRequest("Dozen", "pack", new BigDecimal("12"),
                                new BigDecimal("120.00"), null, null, 10, true))
                .id();
        itemPackOptionService.createPackOption(TENANT_A, mandaziItemId,
                new CreateItemPackOptionRequest(null, "pack", new BigDecimal("18"),
                        new BigDecimal("170.00"), null, null, 20, true));
        option48Id = itemPackOptionService
                .createPackOption(TENANT_A, mandaziItemId,
                        new CreateItemPackOptionRequest("Crate", "pack", new BigDecimal("48"),
                                new BigDecimal("400.00"), null, null, 30, true))
                .id();
    }

    @Test
    void stallProductExposesPacksWithOfferOverrides() throws Exception {
        Supplier jacob = saveSupplier("jacob", "Jacob's Cakes");
        String linkId = saveLink(jacob.getId(), mandaziItemId, new BigDecimal("12"), "pack");

        // Override the price of the 12-pack for this link and opt out of the 48-pack.
        saveOffer(linkId, option12Id, new BigDecimal("110.00"), true, 10);
        saveOffer(linkId, option48Id, null, false, 30);

        String slug = marketplaceSlugService.supplierSlug(jacob);
        mockMvc.perform(get("/api/v1/public/marketplace/s/{slug}", slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products.length()").value(1))
                .andExpect(jsonPath("$.products[0].packSize").value(12))
                .andExpect(jsonPath("$.products[0].packUnit").value("pack"))
                .andExpect(jsonPath("$.products[0].packs.length()").value(2))
                .andExpect(jsonPath("$.products[0].packs[0].id").value(option12Id))
                .andExpect(jsonPath("$.products[0].packs[0].unitsPerPack").value(12))
                .andExpect(jsonPath("$.products[0].packs[0].unitPrice").value(110.0))
                .andExpect(jsonPath("$.products[0].packs[0].eachPrice").value(9.17))
                .andExpect(jsonPath("$.products[0].packs[1].unitsPerPack").value(18))
                .andExpect(jsonPath("$.products[0].packs[1].unitPrice").value(170.0))
                .andExpect(jsonPath("$.products[0].packs[1].eachPrice").value(9.44));
    }

    @Test
    void linkWithoutOffersShowsItemDefaults() throws Exception {
        Supplier grace = saveSupplier("grace", "Grace Bakes");
        saveLink(grace.getId(), mandaziItemId, null, null);

        String slug = marketplaceSlugService.supplierSlug(grace);
        mockMvc.perform(get("/api/v1/public/marketplace/s/{slug}", slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[0].packs.length()").value(3))
                .andExpect(jsonPath("$.products[0].packs[0].unitsPerPack").value(12))
                .andExpect(jsonPath("$.products[0].packs[0].unitPrice").value(120.0))
                .andExpect(jsonPath("$.products[0].packs[0].eachPrice").value(10.0))
                .andExpect(jsonPath("$.products[0].packs[2].unitsPerPack").value(48))
                .andExpect(jsonPath("$.products[0].packs[2].unitPrice").value(400.0))
                .andExpect(jsonPath("$.products[0].packs[2].eachPrice").value(8.33));
    }

    @Test
    void unitOnlyProductHasEmptyPacks() throws Exception {
        String ugaliItemId = itemCatalogService
                .createItem(TENANT_A, minimalItem("UGAL-001", "Ugali", goodsTypeId()), null)
                .body()
                .id();
        Supplier jacob = saveSupplier("jacob", "Jacob's Cakes");
        saveLink(jacob.getId(), mandaziItemId, new BigDecimal("12"), "pack");
        saveLink(jacob.getId(), ugaliItemId, null, null);

        String slug = marketplaceSlugService.supplierSlug(jacob);
        mockMvc.perform(get("/api/v1/public/marketplace/s/{slug}", slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products.length()").value(2))
                .andExpect(jsonPath("$.products[0].name").value("Mandazi"))
                .andExpect(jsonPath("$.products[0].packs.length()").value(3))
                .andExpect(jsonPath("$.products[1].name").value("Ugali"))
                .andExpect(jsonPath("$.products[1].packs.length()").value(0));
    }

    private Supplier saveSupplier(String code, String name) {
        Supplier supplier = new Supplier();
        supplier.setBusinessId(TENANT_A);
        supplier.setName(name);
        supplier.setCode(code);
        supplier.setSupplierType("distributor");
        supplier.setStatus("active");
        return supplierRepository.save(supplier);
    }

    private String saveLink(String supplierId, String itemId, BigDecimal legacyPackSize, String legacyPackUnit) {
        SupplierProduct link = new SupplierProduct();
        link.setSupplierId(supplierId);
        link.setItemId(itemId);
        link.setActive(true);
        link.setPackSize(legacyPackSize);
        link.setPackUnit(legacyPackUnit);
        return supplierProductRepository.save(link).getId();
    }

    private void saveOffer(String linkId, String optionId, BigDecimal price, boolean active, int sortOrder) {
        SupplierProductPackOffer offer = new SupplierProductPackOffer();
        offer.setSupplierProductId(linkId);
        offer.setItemPackOptionId(optionId);
        offer.setPackPrice(price);
        offer.setActive(active);
        offer.setSortOrder(sortOrder);
        supplierProductPackOfferRepository.save(offer);
    }

    private static CreateItemRequest minimalItem(String sku, String name, String itemTypeId) {
        return new CreateItemRequest(
                sku,
                null,
                name,
                null,
                itemTypeId,
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
                null);
    }

    private String goodsTypeId() {
        return itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT_A).stream()
                .filter(t -> "goods".equals(t.getTypeKey()))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private void insertBusiness(String id, String slug) {
        Business b = new Business();
        b.setId(id);
        b.setName("Test " + slug);
        b.setSlug(slug);
        b.setSettings("{}");
        businessRepository.save(b);
    }
}
