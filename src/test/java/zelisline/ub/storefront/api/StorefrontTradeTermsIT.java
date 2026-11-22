package zelisline.ub.storefront.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.server.ResponseStatusException;

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
import zelisline.ub.pricing.api.dto.ItemPriceBreakDto;
import zelisline.ub.pricing.api.dto.ItemTradeTermsDto;
import zelisline.ub.pricing.application.ItemTradeTermsService;
import zelisline.ub.pricing.domain.SellingPrice;
import zelisline.ub.pricing.repository.ItemPriceBreakRepository;
import zelisline.ub.pricing.repository.SellingPriceRepository;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.storefront.application.PublicWebCartService;
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
/** ADR 0038, P1-5: minimum order quantity and quantity prices, in the cart and at checkout. */
class StorefrontTradeTermsIT {

    private static final String TENANT = "d1d1d1d1-d1d1-d1d1-d1d1-d1d1d1d1d1d1";
    private static final String SLUG = "trade-terms-it";

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
    private InventoryBatchRepository inventoryBatchRepository;

    @Autowired
    private WebCartRepository webCartRepository;

    @Autowired
    private WebCartLineRepository webCartLineRepository;

    @Autowired
    private CatalogBootstrapService catalogBootstrapService;

    @Autowired
    private ItemCatalogService itemCatalogService;

    @Autowired
    private ItemTradeTermsService tradeTermsService;

    @Autowired
    private ItemPriceBreakRepository priceBreakRepository;

    @Autowired
    private PublicWebCartService cartService;

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
        priceBreakRepository.deleteAll();
        sellingPriceRepository.deleteAll();
        inventoryBatchRepository.deleteAll();
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
                        null, null, null, null, null, null, null, null, null, null, false, null, null, null, null),
                null
        ).body().id();

        patchWebPublished(publishedItemId, true);

        hiddenItemId = itemCatalogService.createItem(
                TENANT,
                new CreateItemRequest(
                        "SKU-HID", null, "Hidden Item", null, goodsTypeId, null, null, null,
                        false, true, true,
                        null, null, null, null, null, null, null, null, null, null, false, null, null, null, null),
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
        batch.setBatchNumber("CART-SEED-1");
        batch.setSourceType("test");
        batch.setSourceId(UUID.randomUUID().toString());
        BigDecimal stockQty = new BigDecimal("100");
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

    private String createCartId() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/public/businesses/" + SLUG + "/carts"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.catalogBranchId").value(branchId))
                .andReturn();
        JsonNode n = objectMapper.readTree(r.getResponse().getContentAsString());
        return n.get("id").asText();
    }

    private static final String OTHER_SHOP = "e2e2e2e2-e2e2-e2e2-e2e2-e2e2e2e2e2e2";

    private ResultActions putLine(String cartId, int quantity) throws Exception {
        return mockMvc.perform(
                post("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId + "/lines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"%s\",\"quantity\":%d}".formatted(publishedItemId, quantity)));
    }

    private static ItemPriceBreakDto from(String quantity, String price) {
        return new ItemPriceBreakDto(new BigDecimal(quantity), new BigDecimal(price));
    }

    @Test
    void belowTheMinimumIsRefusedAndTheMinimumIsShown() throws Exception {
        tradeTermsService.replace(TENANT, publishedItemId, new ItemTradeTermsDto(new BigDecimal("6"), null));
        String cartId = createCartId();

        putLine(cartId, 2).andExpect(status().isBadRequest());
        putLine(cartId, 6)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].minOrderQty").value(6))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(99.5));

        mockMvc.perform(get("/api/v1/public/businesses/" + SLUG + "/catalog/items/" + publishedItemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minOrderQty").value(6))
                .andExpect(jsonPath("$.priceBreaks").doesNotExist());
    }

    @Test
    void aLargerQuantityEarnsTheLowerPrice() throws Exception {
        tradeTermsService.replace(TENANT, publishedItemId,
                new ItemTradeTermsDto(null, List.of(from("24", "80"), from("12", "90"))));
        String cartId = createCartId();

        putLine(cartId, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].unitPrice").value(99.5))
                .andExpect(jsonPath("$.lines[0].regularUnitPrice").doesNotExist());
        putLine(cartId, 12)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].unitPrice").value(90.0))
                .andExpect(jsonPath("$.lines[0].regularUnitPrice").value(99.5))
                .andExpect(jsonPath("$.lines[0].lineTotal").value(1080.0))
                .andExpect(jsonPath("$.subtotal").value(1080.0));
        putLine(cartId, 30)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].unitPrice").value(80.0))
                .andExpect(jsonPath("$.subtotal").value(2400.0));

        // What the cart shows is what checkout charges.
        assertThat(cartService.requireCheckoutEligible(SLUG, cartId).grandTotal()).isEqualByComparingTo("2400.00");

        mockMvc.perform(get("/api/v1/public/businesses/" + SLUG + "/catalog/items/" + publishedItemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceBreaks.length()").value(2))
                .andExpect(jsonPath("$.priceBreaks[0].minQty").value(12))
                .andExpect(jsonPath("$.priceBreaks[0].unitPrice").value(90.0));
    }

    @Test
    void aQuantityPriceAboveTheShelfPriceNeverRaisesIt() throws Exception {
        tradeTermsService.replace(TENANT, publishedItemId, new ItemTradeTermsDto(null, List.of(from("12", "150"))));
        String cartId = createCartId();

        putLine(cartId, 12)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].unitPrice").value(99.5));
    }

    @Test
    void aMinimumRaisedAfterTheLineWasAddedStopsCheckout() throws Exception {
        String cartId = createCartId();
        putLine(cartId, 2).andExpect(status().isOk());

        tradeTermsService.replace(TENANT, publishedItemId, new ItemTradeTermsDto(new BigDecimal("6"), null));

        assertThatThrownBy(() -> cartService.requireCheckoutEligible(SLUG, cartId))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void termsAreSavedWholeAndClearedByAnEmptySave() {
        tradeTermsService.replace(TENANT, publishedItemId,
                new ItemTradeTermsDto(new BigDecimal("6"), List.of(from("12", "90"), from("24", "80"))));

        ItemTradeTermsDto saved = tradeTermsService.get(TENANT, publishedItemId);
        assertThat(saved.minOrderQty()).isEqualByComparingTo("6");
        assertThat(saved.priceBreaks()).hasSize(2);
        assertThat(saved.priceBreaks().get(1).unitPrice()).isEqualByComparingTo("80");

        tradeTermsService.replace(TENANT, publishedItemId, new ItemTradeTermsDto(null, List.of()));

        ItemTradeTermsDto cleared = tradeTermsService.get(TENANT, publishedItemId);
        assertThat(cleared.minOrderQty()).isNull();
        assertThat(cleared.priceBreaks()).isEmpty();
    }

    @Test
    void anotherShopCanNeitherReadNorChangeTheseTerms() {
        tradeTermsService.replace(TENANT, publishedItemId,
                new ItemTradeTermsDto(new BigDecimal("6"), List.of(from("12", "90"))));

        assertThatThrownBy(() -> tradeTermsService.get(OTHER_SHOP, publishedItemId))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> tradeTermsService.replace(
                        OTHER_SHOP, publishedItemId, new ItemTradeTermsDto(null, List.of())))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(tradeTermsService.breaksByItem(OTHER_SHOP, List.of(publishedItemId))).isEmpty();

        ItemTradeTermsDto untouched = tradeTermsService.get(TENANT, publishedItemId);
        assertThat(untouched.minOrderQty()).isEqualByComparingTo("6");
        assertThat(untouched.priceBreaks()).hasSize(1);
    }

    @Test
    void termsThatMakeNoSenseAreRefused() {
        // The price must fall as the quantity rises.
        assertRefused(new ItemTradeTermsDto(null, List.of(from("12", "90"), from("24", "95"))));
        // The price for one is the shelf price, not a quantity price.
        assertRefused(new ItemTradeTermsDto(null, List.of(from("1", "90"))));
        // Two prices cannot start at the same quantity.
        assertRefused(new ItemTradeTermsDto(null, List.of(from("12", "90"), from("12", "85"))));
        // A product sold by the piece cannot have a minimum of half a piece.
        assertRefused(new ItemTradeTermsDto(new BigDecimal("2.5"), null));

        assertThat(tradeTermsService.get(TENANT, publishedItemId).priceBreaks()).isEmpty();
    }

    private void assertRefused(ItemTradeTermsDto terms) {
        assertThatThrownBy(() -> tradeTermsService.replace(TENANT, publishedItemId, terms))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
