package zelisline.ub.storefront.api;

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
import zelisline.ub.storefront.WebOrderStatuses;
import zelisline.ub.storefront.repository.WebCartLineRepository;
import zelisline.ub.storefront.repository.WebCartRepository;
import zelisline.ub.storefront.repository.WebOrderLineRepository;
import zelisline.ub.storefront.repository.WebOrderRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PublicWebCheckoutIT {

    private static final String TENANT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String SLUG = "public-checkout-it";

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
    private WebOrderRepository webOrderRepository;

    @Autowired
    private WebOrderLineRepository webOrderLineRepository;

    @Autowired
    private CatalogBootstrapService catalogBootstrapService;

    @Autowired
    private ItemCatalogService itemCatalogService;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private String branchId;
    private String pricedItemId;
    private String unpricedPublishedItemId;

    @BeforeEach
    void seed() {
        webOrderLineRepository.deleteAll();
        webOrderRepository.deleteAll();
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
        b.setName("Checkout Shop");
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
        category.setName("Snacks");
        category.setSlug("snacks");
        category.setPosition(0);
        String categoryId = categoryRepository.save(category).getId();

        pricedItemId = itemCatalogService.createItem(
                        TENANT,
                        new CreateItemRequest(
                                "SKU-P", null, "Priced Item", null, goodsTypeId, categoryId, null, null,
                                false, true, true,
                                null, null, null, null, null, null, null, null, null, false, null),
                        null)
                .body()
                .id();

        unpricedPublishedItemId = itemCatalogService.createItem(
                        TENANT,
                        new CreateItemRequest(
                                "SKU-U", null, "Unpriced Item", null, goodsTypeId, categoryId, null, null,
                                false, true, true,
                                null, null, null, null, null, null, null, null, null, false, null),
                        null)
                .body()
                .id();

        patchWebPublished(pricedItemId, true);
        patchWebPublished(unpricedPublishedItemId, true);

        SellingPrice sp = new SellingPrice();
        sp.setBusinessId(TENANT);
        sp.setItemId(pricedItemId);
        sp.setBranchId(branchId);
        sp.setPrice(new BigDecimal("10.00"));
        sp.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        sellingPriceRepository.save(sp);
    }

    private void patchWebPublished(String itemId, boolean on) {
        Item row = itemRepository.findById(itemId).orElseThrow();
        row.setWebPublished(on);
        itemRepository.save(row);
    }

    private String createCart() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/public/businesses/" + SLUG + "/carts"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText();
    }

    @Test
    void checkout_createsPendingOrderAndDeletesCart() throws Exception {
        String cartId = createCart();
        mockMvc.perform(
                        post("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId + "/lines")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"itemId\":\"%s\",\"quantity\":3}".formatted(pricedItemId)))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId + "/checkout")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"customerName\":\" Ada \",\"customerPhone\":\"0700456789\",\"customerEmail\":\"ada@test.invalid\",\"notes\":\"pickup pm\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(WebOrderStatuses.PENDING_PAYMENT))
                .andExpect(jsonPath("$.grandTotal").value(30.0))
                .andExpect(jsonPath("$.catalogBranchName").value("Catalog Branch"))
                .andExpect(jsonPath("$.orderId").exists());

        mockMvc.perform(get("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId))
                .andExpect(status().isNotFound());

        org.assertj.core.api.Assertions.assertThat(webOrderRepository.findAll()).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(webOrderLineRepository.findAll()).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(webCartRepository.count()).isZero();
    }

    @Test
    void checkout_emptyCart_returns400() throws Exception {
        String cartId = createCart();
        mockMvc.perform(
                        post("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId + "/checkout")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"customerName\":\"A\",\"customerPhone\":\"1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void checkout_missingSellPrice_returns400() throws Exception {
        String cartId = createCart();
        mockMvc.perform(
                        post("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId + "/lines")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"itemId\":\"%s\",\"quantity\":1}".formatted(unpricedPublishedItemId)))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId + "/checkout")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"customerName\":\"A\",\"customerPhone\":\"1\"}"))
                .andExpect(status().isBadRequest());

        org.assertj.core.api.Assertions.assertThat(webOrderRepository.count()).isZero();
    }

    @Test
    void checkout_invalidEmail_returns400() throws Exception {
        String cartId = createCart();
        mockMvc.perform(
                        post("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId + "/lines")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"itemId\":\"%s\",\"quantity\":1}".formatted(pricedItemId)))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId + "/checkout")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"customerName\":\"A\",\"customerPhone\":\"1\",\"customerEmail\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());
    }
}
