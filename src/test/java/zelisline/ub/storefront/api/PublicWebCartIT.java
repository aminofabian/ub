package zelisline.ub.storefront.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import zelisline.ub.storefront.repository.WebCartLineRepository;
import zelisline.ub.storefront.repository.WebCartRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PublicWebCartIT {

    private static final String TENANT = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String SLUG = "public-cart-it";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    private WebCartRepository webCartRepository;

    @Autowired
    private WebCartLineRepository webCartLineRepository;

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
        webCartLineRepository.deleteAll();
        webCartRepository.deleteAll();
        sellingPriceRepository.deleteAll();
        itemRepository.deleteAll();
        categoryRepository.deleteAll();
        itemTypeRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Cart Shop");
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

    private String createCartId() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/public/businesses/" + SLUG + "/carts"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.catalogBranchId").value(branchId))
                .andReturn();
        JsonNode n = objectMapper.readTree(r.getResponse().getContentAsString());
        return n.get("id").asText();
    }

    @Test
    void create_thenUpsertLine_returnsPricedCart() throws Exception {
        String cartId = createCartId();
        mockMvc.perform(
                        post("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId + "/lines")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"itemId\":\"%s\",\"quantity\":2}".formatted(publishedItemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].itemId").value(publishedItemId))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(99.5))
                .andExpect(jsonPath("$.lines[0].lineTotal").value(199.0))
                .andExpect(jsonPath("$.subtotal").value(199.0));

        mockMvc.perform(get("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(1));
    }

    @Test
    void upsertUnpublishedItem_returns400() throws Exception {
        String cartId = createCartId();
        mockMvc.perform(
                        post("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId + "/lines")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"itemId\":\"%s\",\"quantity\":1}".formatted(hiddenItemId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void upsertZeroQuantity_removesLine() throws Exception {
        String cartId = createCartId();
        mockMvc.perform(
                        post("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId + "/lines")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"itemId\":\"%s\",\"quantity\":1}".formatted(publishedItemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(1));

        mockMvc.perform(
                        post("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId + "/lines")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"itemId\":\"%s\",\"quantity\":0}".formatted(publishedItemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(0))
                .andExpect(jsonPath("$.subtotal").doesNotExist());
    }

    @Test
    void deleteLine_removesRow() throws Exception {
        String cartId = createCartId();
        mockMvc.perform(
                        post("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId + "/lines")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"itemId\":\"%s\",\"quantity\":1}".formatted(publishedItemId)))
                .andExpect(status().isOk());

        mockMvc.perform(
                        delete(
                                "/api/v1/public/businesses/"
                                        + SLUG
                                        + "/carts/"
                                        + cartId
                                        + "/lines/"
                                        + publishedItemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(0));
    }

    @Test
    void unknownCart_returns404() throws Exception {
        createCartId();
        mockMvc.perform(get("/api/v1/public/businesses/" + SLUG + "/carts/00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isNotFound());
    }
}
