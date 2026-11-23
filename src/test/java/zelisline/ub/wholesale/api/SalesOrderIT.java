package zelisline.ub.wholesale.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import zelisline.ub.finance.domain.JournalEntry;
import zelisline.ub.finance.domain.JournalLine;
import zelisline.ub.finance.repository.JournalEntryRepository;
import zelisline.ub.finance.repository.JournalLineRepository;
import zelisline.ub.marketplace.api.dto.CreateSupplierPortalOrderRequest;
import zelisline.ub.marketplace.api.dto.SubmitBasketRequest;
import zelisline.ub.marketplace.api.dto.SubmitBasketResponse;
import zelisline.ub.marketplace.application.MarketplaceBasketService;
import zelisline.ub.marketplace.application.MarketplaceBuyerSessionService;
import zelisline.ub.marketplace.domain.MarketplaceSupplierProduct;
import zelisline.ub.marketplace.repository.MarketplaceSupplierProductRepository;
import zelisline.ub.marketplace.application.SupplierPortalOrdersService;
import zelisline.ub.marketplace.domain.BusinessSupplierConnection;
import zelisline.ub.marketplace.domain.BusinessSupplierConnectionStatuses;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.MarketplaceSupplierStatuses;
import zelisline.ub.marketplace.repository.BusinessSupplierConnectionRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.pricing.api.dto.ItemPriceBreakDto;
import zelisline.ub.pricing.api.dto.ItemTradeTermsDto;
import zelisline.ub.pricing.application.ItemTradeTermsService;
import zelisline.ub.pricing.domain.SellingPrice;
import zelisline.ub.pricing.repository.ItemPriceBreakRepository;
import zelisline.ub.pricing.repository.SellingPriceRepository;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.domain.PurchaseOrder;
import zelisline.ub.purchasing.domain.PurchaseOrderLine;
import zelisline.ub.purchasing.repository.PurchaseOrderLineRepository;
import zelisline.ub.purchasing.repository.PurchaseOrderRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.storefront.repository.WebCartLineRepository;
import zelisline.ub.storefront.repository.WebCartRepository;
import zelisline.ub.tenancy.application.TenancyService;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;
import zelisline.ub.trade.api.dto.MakePurchaseOrderRequest;
import zelisline.ub.trade.api.dto.RecordReceivedRequest;
import zelisline.ub.trade.api.dto.TradeOrderPurchaseOrderResponse;
import zelisline.ub.trade.api.dto.TradeOrderResponse;
import zelisline.ub.trade.application.TradeOrderService;
import zelisline.ub.trade.repository.TradeOrderEventRepository;
import zelisline.ub.trade.repository.TradeOrderLineRepository;
import zelisline.ub.trade.repository.TradeOrderRepository;
import zelisline.ub.wholesale.api.dto.AnswerSalesOrderRequest;
import zelisline.ub.wholesale.api.dto.DecideTradeAccountRequest;
import zelisline.ub.wholesale.api.dto.ReceivablesAgeingResponse;
import zelisline.ub.wholesale.api.dto.RecordSalesReceiptRequest;
import zelisline.ub.wholesale.api.dto.SalesFiguresResponse;
import zelisline.ub.wholesale.api.dto.SalesInvoiceResponse;
import zelisline.ub.wholesale.api.dto.StandingOrderResponse;
import zelisline.ub.wholesale.api.dto.TradeAccountPriceDto;
import zelisline.ub.wholesale.api.dto.TradeAccountResponse;
import zelisline.ub.wholesale.api.dto.SalesOrderResponse;
import zelisline.ub.wholesale.application.SalesFiguresService;
import zelisline.ub.wholesale.application.SalesInvoiceService;
import zelisline.ub.wholesale.application.StandingOrderService;
import zelisline.ub.wholesale.repository.StandingOrderLineRepository;
import zelisline.ub.wholesale.repository.StandingOrderRepository;
import zelisline.ub.wholesale.repository.TradeAccountPriceRepository;
import zelisline.ub.wholesale.application.SalesOrderService;
import zelisline.ub.wholesale.repository.SalesCreditNoteRepository;
import zelisline.ub.wholesale.repository.SalesInvoiceRepository;
import zelisline.ub.wholesale.repository.SalesReceiptRepository;
import zelisline.ub.wholesale.application.TradeAccountService;
import zelisline.ub.wholesale.repository.TradeAccountRepository;
import zelisline.ub.wholesale.repository.SalesOrderLineRepository;
import zelisline.ub.wholesale.repository.SalesOrderRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
/** ADR 0040: a trade order placed from a supplier's storefront cart. */
class SalesOrderIT {

    private static final String TENANT = "d2d2d2d2-d2d2-d2d2-d2d2-d2d2d2d2d2d2";
    private static final String SLUG = "sales-order-it";

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
    private SalesOrderService salesOrderService;

    @Autowired
    private TradeAccountService tradeAccountService;

    @Autowired
    private SalesInvoiceService salesInvoiceService;

    @Autowired
    private SalesFiguresService salesFiguresService;

    @Autowired
    private StandingOrderService standingOrderService;

    @Autowired
    private StandingOrderRepository standingOrderRepository;

    @Autowired
    private StandingOrderLineRepository standingOrderLineRepository;

    @Autowired
    private TradeAccountPriceRepository tradeAccountPriceRepository;

    @Autowired
    private SalesInvoiceRepository salesInvoiceRepository;

    @Autowired
    private SalesReceiptRepository salesReceiptRepository;

    @Autowired
    private SalesCreditNoteRepository salesCreditNoteRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private JournalLineRepository journalLineRepository;

    @Autowired
    private TradeOrderService tradeOrderService;

    @Autowired
    private SupplierPortalOrdersService supplierPortalOrdersService;

    @Autowired
    private MarketplaceBasketService basketService;

    @Autowired
    private MarketplaceSupplierProductRepository passportProductRepository;

    /** Sign-in is ADR 0043's own test; here the buyer is already signed in for BUYER_SHOP. */
    @MockitoBean
    private MarketplaceBuyerSessionService buyerSessionService;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private PurchaseOrderLineRepository purchaseOrderLineRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private TradeOrderRepository tradeOrderRepository;

    @Autowired
    private TradeOrderLineRepository tradeOrderLineRepository;

    @Autowired
    private TradeOrderEventRepository tradeOrderEventRepository;

    @Autowired
    private MarketplaceSupplierRepository marketplaceSupplierRepository;

    @Autowired
    private BusinessSupplierConnectionRepository connectionRepository;

    @Autowired
    private TradeAccountRepository tradeAccountRepository;

    @Autowired
    private SalesOrderRepository salesOrderRepository;

    @Autowired
    private SalesOrderLineRepository salesOrderLineRepository;

    @Autowired
    private TenancyService tenancyService;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private String branchId;
    private String publishedItemId;
    private String hiddenItemId;

    @BeforeEach
    void seed() {
        standingOrderLineRepository.deleteAll();
        standingOrderRepository.deleteAll();
        tradeAccountPriceRepository.deleteAll();
        salesCreditNoteRepository.deleteAll();
        salesReceiptRepository.deleteAll();
        salesInvoiceRepository.deleteAll();
        purchaseOrderLineRepository.deleteAll();
        purchaseOrderRepository.deleteAll();
        tradeOrderEventRepository.deleteAll();
        tradeOrderLineRepository.deleteAll();
        tradeOrderRepository.deleteAll();
        connectionRepository.deleteAll();
        passportProductRepository.deleteAll();
        marketplaceSupplierRepository.deleteAll();
        salesOrderLineRepository.deleteAll();
        salesOrderRepository.deleteAll();
        tradeAccountRepository.deleteAll();
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

    private static final String OTHER_SHOP = "e3e3e3e3-e3e3-e3e3-e3e3-e3e3e3e3e3e3";
    private static final String BUYER = "{\"buyerName\":\"Mama Njeri\",\"buyerPhone\":\"%s\",\"buyerBusinessName\":\"Njeri Kiosk\"}";

    private void makeThisASuppliersShop() {
        tenancyService.changeStoreTypesForTenant(TENANT, List.of("other", "supplier"));
    }

    private String cartWith(int quantity) throws Exception {
        String cartId = createCartId();
        mockMvc.perform(
                        post("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId + "/lines")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"itemId\":\"%s\",\"quantity\":%d}".formatted(publishedItemId, quantity)))
                .andExpect(status().isOk());
        return cartId;
    }

    private ResultActions place(String cartId, String phone) throws Exception {
        return mockMvc.perform(
                post("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId + "/trade-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BUYER.formatted(phone)));
    }

    private BigDecimal stockLeft() {
        return inventoryBatchRepository.findAll().stream()
                .map(InventoryBatch::getQuantityRemaining)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void anOrdinaryShopDoesNotTakeTradeOrders() throws Exception {
        String cartId = cartWith(2);

        place(cartId, "0700000001").andExpect(status().isConflict());

        assertThat(salesOrderRepository.count()).isZero();
        mockMvc.perform(get("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId))
                .andExpect(status().isOk());
    }

    @Test
    void placingTakesNoStockAndKeepsThePricesTheCartShowed() throws Exception {
        makeThisASuppliersShop();
        tradeTermsService.replace(TENANT, publishedItemId,
                new ItemTradeTermsDto(null, List.of(new ItemPriceBreakDto(new BigDecimal("12"), new BigDecimal("90")))));
        String cartId = cartWith(12);

        MvcResult placed = place(cartId, "0700000002")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("placed"))
                .andExpect(jsonPath("$.total").value(1080.0))
                .andExpect(jsonPath("$.orderCode").exists())
                .andReturn();
        String orderId = objectMapper.readTree(placed.getResponse().getContentAsString()).get("orderId").asText();

        // Nothing left the shelf, and the cart is spent.
        assertThat(stockLeft()).isEqualByComparingTo("100");
        mockMvc.perform(get("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId))
                .andExpect(status().isNotFound());

        SalesOrderResponse order = salesOrderService.get(TENANT, orderId);
        assertThat(order.buyerName()).isEqualTo("Mama Njeri");
        assertThat(order.buyerBusinessName()).isEqualTo("Njeri Kiosk");
        assertThat(order.total()).isEqualByComparingTo("1080.00");
        assertThat(order.lines()).hasSize(1);
        assertThat(order.lines().get(0).itemName()).isEqualTo("Published Item");
        assertThat(order.lines().get(0).quantity()).isEqualByComparingTo("12");
        assertThat(order.lines().get(0).unitPrice()).isEqualByComparingTo("90");
        assertThat(order.lines().get(0).status()).isEqualTo("pending");

        assertThat(salesOrderService.list(TENANT, null)).hasSize(1);
        assertThat(salesOrderService.list(TENANT, "placed")).hasSize(1);
        assertThat(salesOrderService.list(TENANT, "accepted")).isEmpty();
    }

    @Test
    void anotherShopCanNeitherSeeNorListTheOrder() throws Exception {
        makeThisASuppliersShop();
        MvcResult placed = place(cartWith(2), "0700000003").andExpect(status().isCreated()).andReturn();
        String orderId = objectMapper.readTree(placed.getResponse().getContentAsString()).get("orderId").asText();

        assertThatThrownBy(() -> salesOrderService.get(OTHER_SHOP, orderId))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(salesOrderService.list(OTHER_SHOP, null)).isEmpty();
    }

    @Test
    void oneBuyerCannotPileUpUnansweredOrders() throws Exception {
        makeThisASuppliersShop();
        for (int i = 0; i < 5; i++) {
            place(cartWith(1), "0700000004").andExpect(status().isCreated());
        }

        place(cartWith(1), "0700000004").andExpect(status().isTooManyRequests());
        // Somebody else is not held back by it.
        place(cartWith(1), "0700000005").andExpect(status().isCreated());

        assertThat(salesOrderRepository.count()).isEqualTo(6);
    }

    @Test
    void aCartBelowTheMinimumIsNotAccepted() throws Exception {
        makeThisASuppliersShop();
        String cartId = cartWith(2);
        tradeTermsService.replace(TENANT, publishedItemId, new ItemTradeTermsDto(new BigDecimal("6"), null));

        place(cartId, "0700000006").andExpect(status().isBadRequest());

        assertThat(salesOrderRepository.count()).isZero();
    }

    @Test
    void aBuyerMustLeaveANameAndAPhone() throws Exception {
        makeThisASuppliersShop();
        String cartId = cartWith(2);

        mockMvc.perform(
                        post("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId + "/trade-order")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"buyerName\":\"Mama Njeri\"}"))
                .andExpect(status().isBadRequest());

        assertThat(salesOrderRepository.count()).isZero();
    }

    // ── Answering, dispatch and cancelling ─────────────────────────────────────

    private String placedOrder(int quantity, String phone) throws Exception {
        MvcResult placed = place(cartWith(quantity), phone).andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(placed.getResponse().getContentAsString()).get("orderId").asText();
    }

    private AnswerSalesOrderRequest supplying(String orderId, String quantity) {
        String lineId = salesOrderService.get(TENANT, orderId).lines().get(0).id();
        return new AnswerSalesOrderRequest(
                List.of(new AnswerSalesOrderRequest.Line(lineId, new BigDecimal(quantity))));
    }

    private static void assertStatus(Runnable call, HttpStatus expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(expected));
    }

    @Test
    void theSupplierAcceptsInFullThenDispatches() throws Exception {
        makeThisASuppliersShop();
        String orderId = placedOrder(12, "0700000010");
        assertThat(stockLeft()).isEqualByComparingTo("100");

        SalesOrderResponse accepted = salesOrderService.answer(TENANT, orderId, supplying(orderId, "12"));

        assertThat(accepted.status()).isEqualTo("accepted");
        assertThat(accepted.lines().get(0).status()).isEqualTo("accepted");
        assertThat(accepted.lines().get(0).acceptedQty()).isEqualByComparingTo("12");
        assertThat(accepted.total()).isEqualByComparingTo("1194.00");
        // Stock leaves the shelf when the supplier says yes, not before.
        assertThat(stockLeft()).isEqualByComparingTo("88");

        assertThat(salesOrderService.dispatch(TENANT, orderId).status()).isEqualTo("dispatched");
        // Gone is gone: a dispatched order is not cancelled here.
        assertStatus(() -> salesOrderService.cancel(TENANT, orderId), HttpStatus.CONFLICT);
        assertThat(stockLeft()).isEqualByComparingTo("88");
    }

    @Test
    void aPartAcceptedOrderOwesOnlyWhatWasAcceptedAndCancellingPutsItBack() throws Exception {
        makeThisASuppliersShop();
        String orderId = placedOrder(10, "0700000011");

        SalesOrderResponse part = salesOrderService.answer(TENANT, orderId, supplying(orderId, "4"));

        assertThat(part.status()).isEqualTo("part_accepted");
        assertThat(part.lines().get(0).quantity()).isEqualByComparingTo("10");
        assertThat(part.lines().get(0).acceptedQty()).isEqualByComparingTo("4");
        assertThat(part.total()).isEqualByComparingTo("398.00");
        assertThat(stockLeft()).isEqualByComparingTo("96");

        assertThat(salesOrderService.cancel(TENANT, orderId).status()).isEqualTo("cancelled");
        assertThat(stockLeft()).isEqualByComparingTo("100");
        // Cancelling again neither works nor adds the stock a second time.
        assertStatus(() -> salesOrderService.cancel(TENANT, orderId), HttpStatus.CONFLICT);
        assertThat(stockLeft()).isEqualByComparingTo("100");
    }

    @Test
    void rejectingTakesNothingAndCannotBeDispatched() throws Exception {
        makeThisASuppliersShop();
        String orderId = placedOrder(5, "0700000012");

        SalesOrderResponse rejected = salesOrderService.answer(TENANT, orderId, supplying(orderId, "0"));

        assertThat(rejected.status()).isEqualTo("rejected");
        assertThat(rejected.lines().get(0).status()).isEqualTo("rejected");
        assertThat(rejected.total()).isEqualByComparingTo("0");
        assertThat(stockLeft()).isEqualByComparingTo("100");
        assertStatus(() -> salesOrderService.dispatch(TENANT, orderId), HttpStatus.CONFLICT);
    }

    @Test
    void aPlacedOrderCanBeCalledOffAndAnAnswerIsGivenOnce() throws Exception {
        makeThisASuppliersShop();
        String calledOff = placedOrder(3, "0700000013");
        assertThat(salesOrderService.cancel(TENANT, calledOff).status()).isEqualTo("cancelled");
        assertStatus(() -> salesOrderService.answer(TENANT, calledOff, supplying(calledOff, "3")), HttpStatus.CONFLICT);

        String answered = placedOrder(3, "0700000013");
        salesOrderService.answer(TENANT, answered, supplying(answered, "3"));
        assertStatus(() -> salesOrderService.answer(TENANT, answered, supplying(answered, "1")), HttpStatus.CONFLICT);
        assertThat(stockLeft()).isEqualByComparingTo("97");
    }

    @Test
    void anAnswerThatDoesNotFitTheOrderChangesNothing() throws Exception {
        makeThisASuppliersShop();
        String orderId = placedOrder(5, "0700000014");

        // More than was ordered.
        assertStatus(() -> salesOrderService.answer(TENANT, orderId, supplying(orderId, "6")), HttpStatus.BAD_REQUEST);
        // Half a piece of something sold by the piece.
        assertStatus(() -> salesOrderService.answer(TENANT, orderId, supplying(orderId, "2.5")), HttpStatus.BAD_REQUEST);
        // A line that is not on this order.
        assertStatus(
                () -> salesOrderService.answer(TENANT, orderId, new AnswerSalesOrderRequest(
                        List.of(new AnswerSalesOrderRequest.Line(UUID.randomUUID().toString(), BigDecimal.ONE)))),
                HttpStatus.BAD_REQUEST);

        assertThat(salesOrderService.get(TENANT, orderId).status()).isEqualTo("placed");
        assertThat(stockLeft()).isEqualByComparingTo("100");
    }

    @Test
    void anotherShopCannotAnswerDispatchOrCancelTheOrder() throws Exception {
        makeThisASuppliersShop();
        String orderId = placedOrder(5, "0700000015");
        AnswerSalesOrderRequest answer = supplying(orderId, "5");

        assertStatus(() -> salesOrderService.answer(OTHER_SHOP, orderId, answer), HttpStatus.NOT_FOUND);
        assertStatus(() -> salesOrderService.dispatch(OTHER_SHOP, orderId), HttpStatus.NOT_FOUND);
        assertStatus(() -> salesOrderService.cancel(OTHER_SHOP, orderId), HttpStatus.NOT_FOUND);

        assertThat(salesOrderService.get(TENANT, orderId).status()).isEqualTo("placed");
        assertThat(stockLeft()).isEqualByComparingTo("100");
    }

    // ── What the buyer sees ───────────────────────────────────────────────────

    @Test
    void theCartSaysWhetherTheShopTakesTradeOrders() throws Exception {
        mockMvc.perform(post("/api/v1/public/businesses/" + SLUG + "/carts"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tradeOrders").doesNotExist());

        makeThisASuppliersShop();

        mockMvc.perform(post("/api/v1/public/businesses/" + SLUG + "/carts"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tradeOrders").value(true));
    }

    @Test
    void theBuyerFollowsTheOrderByItsIdAndGetsNothingPersonalBack() throws Exception {
        makeThisASuppliersShop();
        String orderId = placedOrder(10, "0700000020");
        salesOrderService.answer(TENANT, orderId, supplying(orderId, "4"));

        String body = mockMvc.perform(get("/api/v1/public/businesses/" + SLUG + "/trade-orders/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("part_accepted"))
                .andExpect(jsonPath("$.total").value(398.0))
                .andExpect(jsonPath("$.lines[0].itemName").value("Published Item"))
                .andExpect(jsonPath("$.lines[0].quantity").value(10))
                .andExpect(jsonPath("$.lines[0].acceptedQty").value(4))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain("0700000020").doesNotContain("Mama Njeri");

        mockMvc.perform(get("/api/v1/public/businesses/" + SLUG + "/trade-orders/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    // ── Trade accounts ────────────────────────────────────────────────────────

    private ResultActions applyForAccount(String phone) throws Exception {
        return mockMvc.perform(
                post("/api/v1/public/businesses/" + SLUG + "/trade-accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"buyerName\":\"Mama Njeri\",\"buyerPhone\":\"%s\",\"businessName\":\"Njeri Kiosk\"}"
                                .formatted(phone)));
    }

    @Test
    void anOrdinaryShopKeepsNoTradeAccounts() throws Exception {
        applyForAccount("0700000030").andExpect(status().isConflict());
        assertThat(tradeAccountRepository.count()).isZero();
    }

    @Test
    void aBuyerWithAnApprovedAccountOrdersOnCredit() throws Exception {
        makeThisASuppliersShop();
        applyForAccount("0700000030")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.received").value(true));
        List<TradeAccountResponse> applied = tradeAccountService.list(TENANT, "applied");
        assertThat(applied).hasSize(1);
        assertThat(applied.get(0).businessName()).isEqualTo("Njeri Kiosk");

        // Asking is not having: until the supplier approves, the buyer pays up front.
        String before = placedOrder(2, "0700000030");
        assertThat(salesOrderService.get(TENANT, before).paymentTerms()).isEqualTo("prepaid");

        TradeAccountResponse approved = tradeAccountService.decide(TENANT, applied.get(0).id(),
                new DecideTradeAccountRequest("approved", new BigDecimal("50000"), 30));
        assertThat(approved.status()).isEqualTo("approved");
        assertThat(approved.termsDays()).isEqualTo(30);

        // The same number written another way is still the same account.
        String after = placedOrder(2, "+254 700 000 030");
        assertThat(salesOrderService.get(TENANT, after).paymentTerms()).isEqualTo("credit");
        // Somebody else's number is not.
        String stranger = placedOrder(2, "0700000031");
        assertThat(salesOrderService.get(TENANT, stranger).paymentTerms()).isEqualTo("prepaid");

        // Applying again neither resets the decision nor tells the caller what it was.
        applyForAccount("0700000030")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").doesNotExist());
        assertThat(tradeAccountService.list(TENANT, "approved")).hasSize(1);
        assertThat(tradeAccountRepository.count()).isEqualTo(1);
    }

    @Test
    void anotherShopCanNeitherSeeNorDecideAnAccount() throws Exception {
        makeThisASuppliersShop();
        applyForAccount("0700000032").andExpect(status().isAccepted());
        String accountId = tradeAccountService.list(TENANT, null).get(0).id();

        assertThat(tradeAccountService.list(OTHER_SHOP, null)).isEmpty();
        assertStatus(
                () -> tradeAccountService.decide(
                        OTHER_SHOP, accountId, new DecideTradeAccountRequest("approved", null, null)),
                HttpStatus.NOT_FOUND);
        assertThat(tradeAccountService.approvedFor(OTHER_SHOP, "0700000032")).isEmpty();
        assertThat(tradeAccountService.list(TENANT, null).get(0).status()).isEqualTo("applied");
    }

    @Test
    void theOrderCapCountsOneNumberHoweverItIsWritten() throws Exception {
        makeThisASuppliersShop();
        for (int i = 0; i < 5; i++) {
            place(cartWith(1), "0700000033").andExpect(status().isCreated());
        }
        place(cartWith(1), "+254700000033").andExpect(status().isTooManyRequests());
    }

    // ── The trade lane: an accepted order reaches a connected shop ─────────────

    private static final String BUYER_SHOP = "b4b4b4b4-b4b4-b4b4-b4b4-b4b4b4b4b4b4";
    private static final String STRANGER_SHOP = "c5c5c5c5-c5c5-c5c5-c5c5-c5c5c5c5c5c5";

    private void saveShop(String id, String name, String slug) {
        Business shop = new Business();
        shop.setId(id);
        shop.setName(name);
        shop.setSlug(slug);
        shop.setSettings("{}");
        businessRepository.save(shop);
    }

    /** This shop becomes a supplier's own shop, and BUYER_SHOP has an active connection to it. */
    private String aSupplierWithOneConnectedShop() {
        makeThisASuppliersShop();
        MarketplaceSupplier passport = new MarketplaceSupplier();
        passport.setSupplierNumber("S-770001");
        passport.setName("Lane Wholesalers");
        passport.setUsername("lanewholesalers");
        passport.setStatus(MarketplaceSupplierStatuses.ACTIVE);
        passport.setBusinessId(TENANT);
        String passportId = marketplaceSupplierRepository.save(passport).getId();

        saveShop(BUYER_SHOP, "Buyer Mini Mart", "lane-buyer");
        saveShop(STRANGER_SHOP, "Stranger Shop", "lane-stranger");
        connect(BUYER_SHOP, passportId, BusinessSupplierConnectionStatuses.ACTIVE);
        // Asked to connect, never approved by the shop: not a connection.
        connect(STRANGER_SHOP, passportId, BusinessSupplierConnectionStatuses.PENDING);
        return passportId;
    }

    private void connect(String shopId, String passportId, String connectionStatus) {
        BusinessSupplierConnection connection = new BusinessSupplierConnection();
        connection.setBusinessId(shopId);
        connection.setMarketplaceSupplierId(passportId);
        connection.setLocalSupplierId(UUID.randomUUID().toString());
        connection.setStatus(connectionStatus);
        connectionRepository.save(connection);
    }

    private String acceptedOrder(int asked, String supplied, String phone) throws Exception {
        String orderId = placedOrder(asked, phone);
        salesOrderService.answer(TENANT, orderId, supplying(orderId, supplied));
        return orderId;
    }

    @Test
    void aSupplierSendsAnAcceptedOrderToAConnectedShop() throws Exception {
        aSupplierWithOneConnectedShop();
        String orderId = acceptedOrder(10, "4", "0700000040");

        assertThat(tradeOrderService.connectedShops(TENANT))
                .extracting(shop -> shop.businessId())
                .containsExactly(BUYER_SHOP);

        TradeOrderResponse sent = tradeOrderService.sendToShop(TENANT, orderId, BUYER_SHOP);

        assertThat(sent.orderState()).isEqualTo("placed");
        assertThat(sent.deliveryState()).isEqualTo("none");
        assertThat(sent.moneyState()).isEqualTo("unbilled");
        assertThat(sent.supplierName()).isEqualTo("Lane Wholesalers");
        assertThat(sent.total()).isEqualByComparingTo("398.00");
        // Only what was accepted crosses, at the price the order was placed at.
        assertThat(sent.lines()).hasSize(1);
        assertThat(sent.lines().get(0).productName()).isEqualTo("Published Item");
        assertThat(sent.lines().get(0).quantity()).isEqualByComparingTo("4");
        assertThat(sent.lines().get(0).unitPrice()).isEqualByComparingTo("99.50");
        assertThat(sent.events()).extracting(e -> e.kind()).containsExactly("sent");

        // The buyer's shop sees it and nothing personal came with it.
        List<TradeOrderResponse> inbox = tradeOrderService.incoming(BUYER_SHOP, "placed");
        assertThat(inbox).hasSize(1);
        String onTheWire = objectMapper.writeValueAsString(tradeOrderService.incomingOne(BUYER_SHOP, sent.id()));
        assertThat(onTheWire).doesNotContain("0700000040").doesNotContain("Mama Njeri").doesNotContain("Njeri Kiosk");

        // Sending it again changes nothing.
        assertThat(tradeOrderService.sendToShop(TENANT, orderId, BUYER_SHOP).id()).isEqualTo(sent.id());
        assertThat(tradeOrderRepository.count()).isEqualTo(1);
    }

    @Test
    void theBuyerConfirmsAndTheSupplierDispatches() throws Exception {
        aSupplierWithOneConnectedShop();
        String orderId = acceptedOrder(5, "5", "0700000041");
        String tradeId = tradeOrderService.sendToShop(TENANT, orderId, BUYER_SHOP).id();

        TradeOrderResponse confirmed = tradeOrderService.decide(BUYER_SHOP, tradeId, true);
        assertThat(confirmed.orderState()).isEqualTo("confirmed");
        assertStatus(() -> tradeOrderService.decide(BUYER_SHOP, tradeId, false), HttpStatus.CONFLICT);

        salesOrderService.dispatch(TENANT, orderId);

        TradeOrderResponse onItsWay = tradeOrderService.incomingOne(BUYER_SHOP, tradeId);
        assertThat(onItsWay.deliveryState()).isEqualTo("all");
        // Dispatch raises the supplier's invoice, so the shop also sees that it has been billed.
        assertThat(onItsWay.moneyState()).isEqualTo("invoiced");
        assertThat(onItsWay.events()).extracting(e -> e.kind())
                .containsExactly("sent", "confirmed", "dispatched", "invoiced");
        assertThat(onItsWay.events()).extracting(e -> e.eventNo()).containsExactly(1, 2, 3, 4);
        assertThat(onItsWay.events()).extracting(e -> e.actorSide())
                .containsExactly("supplier", "buyer", "supplier", "supplier");
    }

    @Test
    void decliningLeavesTheSuppliersOwnOrderAndStockAlone() throws Exception {
        aSupplierWithOneConnectedShop();
        String orderId = acceptedOrder(5, "5", "0700000042");
        String tradeId = tradeOrderService.sendToShop(TENANT, orderId, BUYER_SHOP).id();
        assertThat(stockLeft()).isEqualByComparingTo("95");

        assertThat(tradeOrderService.decide(BUYER_SHOP, tradeId, false).orderState()).isEqualTo("cancelled");

        // The shop said no to the shared record. What the supplier does with its order is its own call.
        assertThat(salesOrderService.get(TENANT, orderId).status()).isEqualTo("accepted");
        assertThat(stockLeft()).isEqualByComparingTo("95");
    }

    @Test
    void cancellingTheSalesOrderCancelsWhatWasSent() throws Exception {
        aSupplierWithOneConnectedShop();
        String orderId = acceptedOrder(5, "5", "0700000043");
        String tradeId = tradeOrderService.sendToShop(TENANT, orderId, BUYER_SHOP).id();

        salesOrderService.cancel(TENANT, orderId);

        TradeOrderResponse cancelled = tradeOrderService.incomingOne(BUYER_SHOP, tradeId);
        assertThat(cancelled.orderState()).isEqualTo("cancelled");
        assertThat(cancelled.events()).extracting(e -> e.kind()).containsExactly("sent", "cancelled");
        assertStatus(() -> tradeOrderService.decide(BUYER_SHOP, tradeId, true), HttpStatus.CONFLICT);
    }

    @Test
    void aSupplierCannotSendToAShopThatHasNotConnected() throws Exception {
        aSupplierWithOneConnectedShop();
        String orderId = acceptedOrder(5, "5", "0700000044");

        // Asked but never approved, not there at all, and itself: all the same answer.
        assertStatus(() -> tradeOrderService.sendToShop(TENANT, orderId, STRANGER_SHOP), HttpStatus.FORBIDDEN);
        assertStatus(() -> tradeOrderService.sendToShop(TENANT, orderId, UUID.randomUUID().toString()), HttpStatus.FORBIDDEN);
        assertStatus(() -> tradeOrderService.sendToShop(TENANT, orderId, TENANT), HttpStatus.FORBIDDEN);
        // An order that has not been accepted has nothing agreed to send.
        String unanswered = placedOrder(2, "0700000045");
        assertStatus(() -> tradeOrderService.sendToShop(TENANT, unanswered, BUYER_SHOP), HttpStatus.CONFLICT);

        assertThat(tradeOrderRepository.count()).isZero();
        assertThat(tradeOrderService.incoming(STRANGER_SHOP, null)).isEmpty();
    }

    @Test
    void noThirdShopCanReadOrAnswerATradeOrder() throws Exception {
        aSupplierWithOneConnectedShop();
        String orderId = acceptedOrder(5, "5", "0700000046");
        String tradeId = tradeOrderService.sendToShop(TENANT, orderId, BUYER_SHOP).id();

        // Another shop on the buying side.
        assertThat(tradeOrderService.incoming(STRANGER_SHOP, null)).isEmpty();
        assertStatus(() -> tradeOrderService.incomingOne(STRANGER_SHOP, tradeId), HttpStatus.NOT_FOUND);
        assertStatus(() -> tradeOrderService.decide(STRANGER_SHOP, tradeId, true), HttpStatus.NOT_FOUND);
        // The supplier is not the buyer of its own order.
        assertStatus(() -> tradeOrderService.incomingOne(TENANT, tradeId), HttpStatus.NOT_FOUND);
        // Another shop on the selling side cannot send or read this sales order.
        assertStatus(() -> tradeOrderService.sendToShop(STRANGER_SHOP, orderId, BUYER_SHOP), HttpStatus.NOT_FOUND);
        assertThat(tradeOrderService.forSalesOrder(STRANGER_SHOP, orderId)).isEmpty();
        assertThat(tradeOrderService.connectedShops(STRANGER_SHOP)).isEmpty();

        assertThat(tradeOrderService.incomingOne(BUYER_SHOP, tradeId).orderState()).isEqualTo("placed");
    }

    @Test
    void aShopThatSuspendedTheSupplierCannotBeSentMoreAndCannotConfirm() throws Exception {
        String passportId = aSupplierWithOneConnectedShop();
        String first = acceptedOrder(2, "2", "0700000047");
        String tradeId = tradeOrderService.sendToShop(TENANT, first, BUYER_SHOP).id();

        BusinessSupplierConnection connection =
                connectionRepository.findByBusinessIdAndMarketplaceSupplierId(BUYER_SHOP, passportId).orElseThrow();
        connection.setStatus(BusinessSupplierConnectionStatuses.SUSPENDED);
        connectionRepository.save(connection);

        String second = acceptedOrder(2, "2", "0700000048");
        assertStatus(() -> tradeOrderService.sendToShop(TENANT, second, BUYER_SHOP), HttpStatus.FORBIDDEN);
        assertStatus(() -> tradeOrderService.decide(BUYER_SHOP, tradeId, true), HttpStatus.CONFLICT);
        // It can still say no.
        assertThat(tradeOrderService.decide(BUYER_SHOP, tradeId, false).orderState()).isEqualTo("cancelled");
    }

    // ── The buyer's own purchase order (P2-2) ────────────────────────────────

    /** Gives BUYER_SHOP what a shop needs to raise a purchase order: a branch, the supplier on its books, a product. */
    private String buyerShopReadyToBuy(String passportId, String barcode) {
        Branch branch = new Branch();
        branch.setBusinessId(BUYER_SHOP);
        branch.setName("Buyer Main");
        branch.setActive(true);
        branchRepository.save(branch);

        Supplier onTheirBooks = new Supplier();
        onTheirBooks.setBusinessId(BUYER_SHOP);
        onTheirBooks.setName("Lane Wholesalers");
        onTheirBooks.setCode("LW-" + UUID.randomUUID().toString().substring(0, 8));
        onTheirBooks.setSupplierType("distributor");
        onTheirBooks.setStatus("active");
        String localSupplierId = supplierRepository.save(onTheirBooks).getId();
        BusinessSupplierConnection connection =
                connectionRepository.findByBusinessIdAndMarketplaceSupplierId(BUYER_SHOP, passportId).orElseThrow();
        connection.setLocalSupplierId(localSupplierId);
        connectionRepository.save(connection);

        catalogBootstrapService.seedDefaultItemTypesIfMissing(BUYER_SHOP);
        String goodsTypeId = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(BUYER_SHOP).getFirst().getId();
        return itemCatalogService.createItem(
                BUYER_SHOP,
                new CreateItemRequest(
                        "BUY-" + UUID.randomUUID().toString().substring(0, 8), barcode, "Their Own Name For It", null,
                        goodsTypeId, null, null, null,
                        false, true, true,
                        null, null, null, null, null, null, null, null, null, null, false, null, null, null, null),
                null
        ).body().id();
    }

    @Test
    void theShopMakesItsOwnPurchaseOrderFromAConfirmedOrder() throws Exception {
        String passportId = aSupplierWithOneConnectedShop();
        String theirItemId = buyerShopReadyToBuy(passportId, null);
        String orderId = acceptedOrder(10, "4", "0700000050");
        String tradeId = tradeOrderService.sendToShop(TENANT, orderId, BUYER_SHOP).id();

        // Not before the shop has said the order is its own.
        assertStatus(() -> tradeOrderService.makePurchaseOrder(BUYER_SHOP, tradeId, null), HttpStatus.CONFLICT);
        tradeOrderService.decide(BUYER_SHOP, tradeId, true);

        // The supplier's product carries no barcode, so nothing matches and nothing is made.
        assertThat(tradeOrderService.matches(BUYER_SHOP, tradeId).get(0).itemId()).isNull();
        assertStatus(() -> tradeOrderService.makePurchaseOrder(BUYER_SHOP, tradeId, null), HttpStatus.CONFLICT);
        // A product from another shop is not one of this shop's products.
        assertStatus(
                () -> tradeOrderService.makePurchaseOrder(BUYER_SHOP, tradeId, new MakePurchaseOrderRequest(
                        null, List.of(new MakePurchaseOrderRequest.Match(1, publishedItemId)))),
                HttpStatus.BAD_REQUEST);
        assertThat(purchaseOrderRepository.count()).isZero();

        TradeOrderPurchaseOrderResponse made = tradeOrderService.makePurchaseOrder(BUYER_SHOP, tradeId,
                new MakePurchaseOrderRequest(null, List.of(new MakePurchaseOrderRequest.Match(1, theirItemId))));

        PurchaseOrder po = purchaseOrderRepository.findByIdAndBusinessId(made.purchaseOrderId(), BUYER_SHOP).orElseThrow();
        assertThat(po.getStatus()).isEqualTo("draft");
        assertThat(po.getSource()).isEqualTo("trade_order");
        List<PurchaseOrderLine> lines = purchaseOrderLineRepository.findByPurchaseOrderIdOrderBySortOrderAscIdAsc(po.getId());
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).getItemId()).isEqualTo(theirItemId);
        assertThat(lines.get(0).getQtyOrdered()).isEqualByComparingTo("4");
        assertThat(lines.get(0).getUnitEstimatedCost()).isEqualByComparingTo("99.50");
        // It is in the buying shop's books only.
        assertThat(purchaseOrderRepository.findByIdAndBusinessId(made.purchaseOrderId(), TENANT)).isEmpty();

        TradeOrderResponse after = tradeOrderService.incomingOne(BUYER_SHOP, tradeId);
        assertThat(after.hasPurchaseOrder()).isTrue();
        assertThat(after.events()).extracting(e -> e.kind()).containsExactly("sent", "confirmed", "purchase_order");
        // Once is enough.
        assertStatus(
                () -> tradeOrderService.makePurchaseOrder(BUYER_SHOP, tradeId, new MakePurchaseOrderRequest(
                        null, List.of(new MakePurchaseOrderRequest.Match(1, theirItemId)))),
                HttpStatus.CONFLICT);
        assertThat(purchaseOrderRepository.count()).isEqualTo(1);
    }

    @Test
    void aLineWithTheSameBarcodeFindsTheShopsProductByItself() throws Exception {
        String passportId = aSupplierWithOneConnectedShop();
        Item sold = itemRepository.findById(publishedItemId).orElseThrow();
        sold.setBarcode("6161100099001");
        itemRepository.save(sold);
        String theirItemId = buyerShopReadyToBuy(passportId, "6161100099001");
        String orderId = acceptedOrder(3, "3", "0700000051");
        String tradeId = tradeOrderService.sendToShop(TENANT, orderId, BUYER_SHOP).id();
        tradeOrderService.decide(BUYER_SHOP, tradeId, true);

        assertThat(tradeOrderService.matches(BUYER_SHOP, tradeId).get(0).itemId()).isEqualTo(theirItemId);

        TradeOrderPurchaseOrderResponse made = tradeOrderService.makePurchaseOrder(BUYER_SHOP, tradeId, null);
        assertThat(purchaseOrderLineRepository.findByPurchaseOrderIdOrderBySortOrderAscIdAsc(made.purchaseOrderId())
                .get(0).getItemId()).isEqualTo(theirItemId);
    }

    @Test
    void noOtherShopCanMakeOrPreviewThePurchaseOrder() throws Exception {
        String passportId = aSupplierWithOneConnectedShop();
        buyerShopReadyToBuy(passportId, null);
        String orderId = acceptedOrder(3, "3", "0700000052");
        String tradeId = tradeOrderService.sendToShop(TENANT, orderId, BUYER_SHOP).id();
        tradeOrderService.decide(BUYER_SHOP, tradeId, true);

        assertStatus(() -> tradeOrderService.matches(STRANGER_SHOP, tradeId), HttpStatus.NOT_FOUND);
        assertStatus(() -> tradeOrderService.makePurchaseOrder(STRANGER_SHOP, tradeId, null), HttpStatus.NOT_FOUND);
        // The supplier cannot write the buyer's purchase order for it.
        assertStatus(() -> tradeOrderService.makePurchaseOrder(TENANT, tradeId, null), HttpStatus.NOT_FOUND);
        assertThat(purchaseOrderRepository.count()).isZero();
    }

    // ── Invoices, receipts and what buyers owe (P2-4) ────────────────────────

    private SalesInvoiceResponse invoiceFor(String orderId) {
        return salesInvoiceService.list(TENANT, false).stream()
                .filter(i -> orderId.equals(i.salesOrderId()))
                .findFirst()
                .orElseThrow();
    }

    private BigDecimal debits(String sourceType, String sourceId) {
        JournalEntry entry = journalEntryRepository
                .findByBusinessIdAndSourceTypeAndSourceId(TENANT, sourceType, sourceId)
                .orElseThrow();
        return journalLineRepository.findByJournalEntryId(entry.getId()).stream()
                .map(JournalLine::getDebit)
                .filter(d -> d != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal credits(String sourceType, String sourceId) {
        JournalEntry entry = journalEntryRepository
                .findByBusinessIdAndSourceTypeAndSourceId(TENANT, sourceType, sourceId)
                .orElseThrow();
        return journalLineRepository.findByJournalEntryId(entry.getId()).stream()
                .map(JournalLine::getCredit)
                .filter(c -> c != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void dispatchingRaisesTheInvoiceAndPostsIt() throws Exception {
        makeThisASuppliersShop();
        String orderId = acceptedOrder(12, "12", "0700000060");
        // Nothing is owed, and nothing is in the books, until the goods leave.
        assertThat(salesInvoiceRepository.count()).isZero();

        salesOrderService.dispatch(TENANT, orderId);

        SalesInvoiceResponse invoice = invoiceFor(orderId);
        assertThat(invoice.status()).isEqualTo("open");
        assertThat(invoice.total()).isEqualByComparingTo("1194.00");
        assertThat(invoice.balance()).isEqualByComparingTo("1194.00");
        // No approved account: due the day it leaves.
        assertThat(invoice.dueOn()).isEqualTo(invoice.issuedOn());
        // Receivable and sale at 1,194; cost of the 12 units (1.00 each) out of stock: the entry balances.
        assertThat(debits("sales_invoice", invoice.id())).isEqualByComparingTo("1206.00");
        assertThat(credits("sales_invoice", invoice.id())).isEqualByComparingTo("1206.00");
        assertThat(salesInvoiceRepository.count()).isEqualTo(1);
    }

    @Test
    void anApprovedAccountGetsItsDaysToPay() throws Exception {
        makeThisASuppliersShop();
        applyForAccount("0700000061").andExpect(status().isAccepted());
        tradeAccountService.decide(TENANT, tradeAccountService.list(TENANT, null).get(0).id(),
                new DecideTradeAccountRequest("approved", null, 30));
        String orderId = acceptedOrder(2, "2", "0700000061");

        salesOrderService.dispatch(TENANT, orderId);

        SalesInvoiceResponse invoice = invoiceFor(orderId);
        assertThat(invoice.dueOn()).isEqualTo(invoice.issuedOn().plusDays(30));
    }

    @Test
    void moneyReceivedBringsTheBalanceDownAndNeverBelowZero() throws Exception {
        makeThisASuppliersShop();
        String orderId = acceptedOrder(10, "10", "0700000062");
        salesOrderService.dispatch(TENANT, orderId);
        String invoiceId = invoiceFor(orderId).id();

        SalesInvoiceResponse part = salesInvoiceService.recordReceipt(TENANT, invoiceId,
                new RecordSalesReceiptRequest(new BigDecimal("400"), "mpesa", "QWE123", null));
        assertThat(part.status()).isEqualTo("part_paid");
        assertThat(part.balance()).isEqualByComparingTo("595.00");
        assertThat(part.receipts()).hasSize(1);
        assertThat(debits("sales_receipt", part.receipts().get(0).id())).isEqualByComparingTo("400.00");

        // More than is owed is refused and changes nothing.
        assertStatus(
                () -> salesInvoiceService.recordReceipt(TENANT, invoiceId,
                        new RecordSalesReceiptRequest(new BigDecimal("600"), "cash", null, null)),
                HttpStatus.BAD_REQUEST);

        SalesInvoiceResponse paid = salesInvoiceService.recordReceipt(TENANT, invoiceId,
                new RecordSalesReceiptRequest(new BigDecimal("595"), "cash", null, null));
        assertThat(paid.status()).isEqualTo("paid");
        assertThat(paid.balance()).isEqualByComparingTo("0");
        assertStatus(
                () -> salesInvoiceService.recordReceipt(TENANT, invoiceId,
                        new RecordSalesReceiptRequest(new BigDecimal("1"), "cash", null, null)),
                HttpStatus.CONFLICT);
        assertThat(salesReceiptRepository.count()).isEqualTo(2);
        assertThat(salesInvoiceService.list(TENANT, true)).isEmpty();
    }

    @Test
    void whatBuyersOweIsAddedUpPerBuyer() throws Exception {
        makeThisASuppliersShop();
        String first = acceptedOrder(10, "10", "0700000063");
        String second = acceptedOrder(2, "2", "+254 700 000 063");
        String other = acceptedOrder(4, "4", "0700000064");
        for (String id : List.of(first, second, other)) {
            salesOrderService.dispatch(TENANT, id);
        }
        salesInvoiceService.recordReceipt(TENANT, invoiceFor(other).id(),
                new RecordSalesReceiptRequest(new BigDecimal("98"), "bank", null, null));

        ReceivablesAgeingResponse owed = salesInvoiceService.ageing(TENANT);

        assertThat(owed.total()).isEqualByComparingTo("1494.00");
        assertThat(owed.buyers()).hasSize(2);
        // The same number written two ways is one buyer, and the biggest debt comes first.
        assertThat(owed.buyers().get(0).total()).isEqualByComparingTo("1194.00");
        assertThat(owed.buyers().get(0).notYetDue()).isEqualByComparingTo("1194.00");
        assertThat(owed.buyers().get(1).total()).isEqualByComparingTo("300.00");
    }

    @Test
    void anotherShopCanNeitherSeeNorTakeMoneyOnAnInvoice() throws Exception {
        makeThisASuppliersShop();
        String orderId = acceptedOrder(5, "5", "0700000065");
        salesOrderService.dispatch(TENANT, orderId);
        String invoiceId = invoiceFor(orderId).id();

        assertThat(salesInvoiceService.list(OTHER_SHOP, false)).isEmpty();
        assertThat(salesInvoiceService.ageing(OTHER_SHOP).buyers()).isEmpty();
        assertStatus(() -> salesInvoiceService.get(OTHER_SHOP, invoiceId), HttpStatus.NOT_FOUND);
        assertStatus(
                () -> salesInvoiceService.recordReceipt(OTHER_SHOP, invoiceId,
                        new RecordSalesReceiptRequest(new BigDecimal("10"), "cash", null, null)),
                HttpStatus.NOT_FOUND);
        assertThat(salesInvoiceService.get(TENANT, invoiceId).balance()).isEqualByComparingTo("497.50");
    }

    @Test
    void theConnectedShopSeesThatItWasBilledAndPaidButNotTheAmountsReceived() throws Exception {
        aSupplierWithOneConnectedShop();
        String orderId = acceptedOrder(4, "4", "0700000066");
        String tradeId = tradeOrderService.sendToShop(TENANT, orderId, BUYER_SHOP).id();
        salesOrderService.dispatch(TENANT, orderId);
        String invoiceId = invoiceFor(orderId).id();

        salesInvoiceService.recordReceipt(TENANT, invoiceId,
                new RecordSalesReceiptRequest(new BigDecimal("100"), "mpesa", "SECRETREF9", null));
        assertThat(tradeOrderService.incomingOne(BUYER_SHOP, tradeId).moneyState()).isEqualTo("part_paid");
        salesInvoiceService.recordReceipt(TENANT, invoiceId,
                new RecordSalesReceiptRequest(new BigDecimal("298"), "cash", null, null));

        TradeOrderResponse seen = tradeOrderService.incomingOne(BUYER_SHOP, tradeId);
        assertThat(seen.moneyState()).isEqualTo("settled");
        assertThat(seen.events()).extracting(e -> e.kind())
                .containsExactly("sent", "dispatched", "invoiced", "payment", "payment");
        assertThat(objectMapper.writeValueAsString(seen)).doesNotContain("SECRETREF9").doesNotContain(invoiceId);
    }

    // ── What arrived, disputes and credit notes (P2-5, P2-6) ─────────────────

    private static RecordReceivedRequest arrived(String quantity) {
        return new RecordReceivedRequest(List.of(new RecordReceivedRequest.Line(1, new BigDecimal(quantity))));
    }

    @Test
    void aFullDeliveryIsSimplyNoted() throws Exception {
        aSupplierWithOneConnectedShop();
        String orderId = acceptedOrder(4, "4", "0700000070");
        String tradeId = tradeOrderService.sendToShop(TENANT, orderId, BUYER_SHOP).id();
        // Nothing can have arrived before it was sent on its way.
        assertStatus(() -> tradeOrderService.recordReceived(BUYER_SHOP, tradeId, arrived("4")), HttpStatus.CONFLICT);
        salesOrderService.dispatch(TENANT, orderId);

        TradeOrderResponse noted = tradeOrderService.recordReceived(BUYER_SHOP, tradeId, arrived("4"));

        assertThat(noted.orderState()).isEqualTo("placed");
        assertThat(noted.lines().get(0).receivedQty()).isEqualByComparingTo("4");
        assertThat(noted.events()).extracting(e -> e.kind()).containsExactly("sent", "dispatched", "invoiced", "received");
        assertThat(noted.escrow()).isNull();
        // Said once.
        assertStatus(() -> tradeOrderService.recordReceived(BUYER_SHOP, tradeId, arrived("3")), HttpStatus.CONFLICT);
    }

    @Test
    void aShortDeliveryIsDisputedThenPutRightWithACreditNote() throws Exception {
        aSupplierWithOneConnectedShop();
        String orderId = acceptedOrder(4, "4", "0700000071");
        String tradeId = tradeOrderService.sendToShop(TENANT, orderId, BUYER_SHOP).id();
        salesOrderService.dispatch(TENANT, orderId);
        String invoiceId = invoiceFor(orderId).id();

        // More than was sent cannot have arrived.
        assertStatus(() -> tradeOrderService.recordReceived(BUYER_SHOP, tradeId, arrived("5")), HttpStatus.BAD_REQUEST);
        TradeOrderResponse disputed = tradeOrderService.recordReceived(BUYER_SHOP, tradeId, arrived("3"));
        assertThat(disputed.orderState()).isEqualTo("disputed");

        // The supplier takes the missing unit off the invoice, and no more than is owed.
        assertStatus(
                () -> salesInvoiceService.issueCreditNote(TENANT, invoiceId, new BigDecimal("500"), "Too much"),
                HttpStatus.BAD_REQUEST);
        SalesInvoiceResponse credited = salesInvoiceService.issueCreditNote(
                TENANT, invoiceId, new BigDecimal("99.50"), "One short on delivery");
        assertThat(credited.amountCredited()).isEqualByComparingTo("99.50");
        assertThat(credited.balance()).isEqualByComparingTo("298.50");
        assertThat(credited.status()).isEqualTo("open");
        String noteId = salesCreditNoteRepository.findAll().get(0).getId();
        assertThat(debits("sales_credit_note", noteId)).isEqualByComparingTo("99.50");
        assertThat(credits("sales_credit_note", noteId)).isEqualByComparingTo("99.50");

        TradeOrderResponse resolved = tradeOrderService.resolveDispute(TENANT, orderId);
        assertThat(resolved.orderState()).isEqualTo("confirmed");
        assertThat(resolved.events()).extracting(e -> e.kind())
                .containsExactly("sent", "dispatched", "invoiced", "received", "shortage", "credit_note", "resolved");
        assertStatus(() -> tradeOrderService.resolveDispute(TENANT, orderId), HttpStatus.CONFLICT);

        // Paying the rest settles it.
        SalesInvoiceResponse paid = salesInvoiceService.recordReceipt(TENANT, invoiceId,
                new RecordSalesReceiptRequest(new BigDecimal("298.50"), "mpesa", null, null));
        assertThat(paid.status()).isEqualTo("paid");
        assertThat(tradeOrderService.incomingOne(BUYER_SHOP, tradeId).moneyState()).isEqualTo("settled");
    }

    @Test
    void aCreditNoteForEverythingOwedClosesTheInvoice() throws Exception {
        makeThisASuppliersShop();
        String orderId = acceptedOrder(2, "2", "0700000072");
        salesOrderService.dispatch(TENANT, orderId);
        String invoiceId = invoiceFor(orderId).id();

        SalesInvoiceResponse closed = salesInvoiceService.issueCreditNote(
                TENANT, invoiceId, new BigDecimal("199"), "Whole delivery sent back");

        assertThat(closed.status()).isEqualTo("paid");
        assertThat(closed.balance()).isEqualByComparingTo("0");
        assertThat(salesInvoiceService.ageing(TENANT).buyers()).isEmpty();
        assertStatus(
                () -> salesInvoiceService.issueCreditNote(TENANT, invoiceId, BigDecimal.ONE, "Again"),
                HttpStatus.CONFLICT);
    }

    @Test
    void noOtherShopCanSayWhatArrivedResolveOrCredit() throws Exception {
        aSupplierWithOneConnectedShop();
        String orderId = acceptedOrder(4, "4", "0700000073");
        String tradeId = tradeOrderService.sendToShop(TENANT, orderId, BUYER_SHOP).id();
        salesOrderService.dispatch(TENANT, orderId);
        String invoiceId = invoiceFor(orderId).id();

        assertStatus(() -> tradeOrderService.recordReceived(STRANGER_SHOP, tradeId, arrived("4")), HttpStatus.NOT_FOUND);
        // The supplier cannot speak for the shop about what arrived.
        assertStatus(() -> tradeOrderService.recordReceived(TENANT, tradeId, arrived("4")), HttpStatus.NOT_FOUND);
        // The shop cannot resolve its own dispute or credit the supplier's invoice.
        assertStatus(() -> tradeOrderService.resolveDispute(BUYER_SHOP, orderId), HttpStatus.NOT_FOUND);
        assertStatus(
                () -> salesInvoiceService.issueCreditNote(BUYER_SHOP, invoiceId, BigDecimal.ONE, "Mine now"),
                HttpStatus.NOT_FOUND);
        assertThat(salesCreditNoteRepository.count()).isZero();
        assertThat(tradeOrderService.incomingOne(BUYER_SHOP, tradeId).lines().get(0).receivedQty()).isNull();
    }

    // ── The old path is closed to a supplier that has a shop (P2-3) ───────────

    @Test
    void aSupplierWithItsOwnShopNoLongerWritesIntoAShopsBooks() throws Exception {
        String passportId = aSupplierWithOneConnectedShop();
        String theirItemId = buyerShopReadyToBuy(passportId, null);
        String localSupplierId = connectionRepository
                .findByBusinessIdAndMarketplaceSupplierId(BUYER_SHOP, passportId).orElseThrow().getLocalSupplierId();

        assertStatus(
                () -> supplierPortalOrdersService.createOrder(passportId, new CreateSupplierPortalOrderRequest(
                        localSupplierId, null, null,
                        List.of(new CreateSupplierPortalOrderRequest.Line(theirItemId, BigDecimal.ONE, BigDecimal.TEN)))),
                HttpStatus.CONFLICT);

        assertThat(purchaseOrderRepository.count()).isZero();
    }

    // ── One basket on the marketplace, and accounts that belong to a shop (P3-3, P3-4) ──

    private static final String BUYER_TOKEN = "signed-in-buyer";

    /** The supplier is listed, its shop sells the published item, and a buyer is signed in for BUYER_SHOP. */
    private String aListedSupplierAndASignedInBuyer(boolean productIsInTheShop) {
        String passportId = aSupplierWithOneConnectedShop();
        MarketplaceSupplier passport = marketplaceSupplierRepository.findById(passportId).orElseThrow();
        passport.setListed(true);
        marketplaceSupplierRepository.save(passport);

        MarketplaceSupplierProduct listed = new MarketplaceSupplierProduct();
        listed.setMarketplaceSupplierId(passportId);
        listed.setName("Published Item");
        listed.setItemId(productIsInTheShop ? publishedItemId : null);
        String productId = passportProductRepository.save(listed).getId();

        org.mockito.Mockito.when(buyerSessionService.requireBuyerFor(BUYER_TOKEN, BUYER_SHOP))
                .thenReturn(new MarketplaceBuyerSessionService.Buyer("254700000080", BUYER_SHOP, "Buyer Mini Mart"));
        org.mockito.Mockito.when(buyerSessionService.requireBuyerFor(BUYER_TOKEN, STRANGER_SHOP))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot order for that shop"));
        return passportId + "|" + productId;
    }

    private static SubmitBasketRequest basket(String buyerShop, String supplierId, String productId, String quantity) {
        return new SubmitBasketRequest(buyerShop, "Mama Njeri", null, List.of(
                new SubmitBasketRequest.Supplier(supplierId, List.of(
                        new SubmitBasketRequest.Line(productId, new BigDecimal(quantity))))));
    }

    @Test
    void oneBasketBecomesAnOrderInEachSuppliersShop() throws Exception {
        String[] ids = aListedSupplierAndASignedInBuyer(true).split("\\|");

        SubmitBasketResponse sent = basketService.submit(BUYER_TOKEN, basket(BUYER_SHOP, ids[0], ids[1], "6"));

        assertThat(sent.results()).hasSize(1);
        SubmitBasketResponse.Result result = sent.results().get(0);
        assertThat(result.placed()).isTrue();
        assertThat(result.total()).isEqualByComparingTo("597.00");
        assertThat(result.shopSlug()).isEqualTo(SLUG);

        SalesOrderResponse order = salesOrderService.get(TENANT, result.orderId());
        assertThat(order.status()).isEqualTo("placed");
        assertThat(order.buyerVerified()).isTrue();
        assertThat(order.buyerBusinessName()).isEqualTo("Buyer Mini Mart");
        // Placing still takes no stock.
        assertThat(stockLeft()).isEqualByComparingTo("100");

        // The shop is connected, so accepting sends it across already confirmed: the shop asked for it.
        salesOrderService.answer(TENANT, result.orderId(), supplying(result.orderId(), "6"));
        List<TradeOrderResponse> inbox = tradeOrderService.incoming(BUYER_SHOP, null);
        assertThat(inbox).hasSize(1);
        assertThat(inbox.get(0).orderState()).isEqualTo("confirmed");
        assertThat(tradeOrderService.incomingOne(BUYER_SHOP, inbox.get(0).id()).events())
                .extracting(e -> e.kind()).containsExactly("sent", "confirmed");
    }

    @Test
    void aBasketIsRefusedForAShopTheBuyerIsNotStaffOf() throws Exception {
        String[] ids = aListedSupplierAndASignedInBuyer(true).split("\\|");

        assertStatus(
                () -> basketService.submit(BUYER_TOKEN, basket(STRANGER_SHOP, ids[0], ids[1], "2")),
                HttpStatus.FORBIDDEN);
        assertThat(salesOrderRepository.count()).isZero();
    }

    @Test
    void whatCannotBeOrderedIsReportedAndNothingIsPlacedForIt() throws Exception {
        String[] ids = aListedSupplierAndASignedInBuyer(false).split("\\|");

        // The product is on the price list but not in the shop.
        SubmitBasketResponse notInShop = basketService.submit(BUYER_TOKEN, basket(BUYER_SHOP, ids[0], ids[1], "2"));
        assertThat(notInShop.results().get(0).placed()).isFalse();
        assertThat(notInShop.results().get(0).message()).isNotBlank();
        // A supplier that does not exist.
        SubmitBasketResponse nobody = basketService.submit(
                BUYER_TOKEN, basket(BUYER_SHOP, UUID.randomUUID().toString(), ids[1], "2"));
        assertThat(nobody.results().get(0).placed()).isFalse();

        assertThat(salesOrderRepository.count()).isZero();
        assertThat(webCartRepository.count()).isZero();
    }

    @Test
    void aShopsOwnAccountBringsCreditADiscountAndALimit() throws Exception {
        String[] ids = aListedSupplierAndASignedInBuyer(true).split("\\|");
        String first = basketService.submit(BUYER_TOKEN, basket(BUYER_SHOP, ids[0], ids[1], "2")).results().get(0).orderId();
        assertThat(salesOrderService.get(TENANT, first).paymentTerms()).isEqualTo("prepaid");

        // The supplier opens an account for the shop that order came from: 10% off, a limit of 1,000.
        TradeAccountResponse account = salesOrderService.decideAccountForOrder(TENANT, first,
                new DecideTradeAccountRequest("approved", new BigDecimal("1000"), 14, new BigDecimal("10")));
        assertThat(account.buyerVerified()).isTrue();
        assertThat(account.discountPercent()).isEqualByComparingTo("10");

        // The next order from that shop is on credit, at the discount: 4 x 89.55.
        String second = basketService.submit(BUYER_TOKEN, basket(BUYER_SHOP, ids[0], ids[1], "4")).results().get(0).orderId();
        SalesOrderResponse discounted = salesOrderService.get(TENANT, second);
        assertThat(discounted.paymentTerms()).isEqualTo("credit");
        assertThat(discounted.lines().get(0).unitPrice()).isEqualByComparingTo("89.55");
        assertThat(discounted.total()).isEqualByComparingTo("358.20");
        assertThat(discounted.overCreditLimit()).isFalse();

        // A third order takes the shop past its limit (358.20 waiting + 895.50). It is placed and marked.
        String third = basketService.submit(BUYER_TOKEN, basket(BUYER_SHOP, ids[0], ids[1], "10")).results().get(0).orderId();
        assertThat(salesOrderService.get(TENANT, third).overCreditLimit()).isTrue();

        // Someone typing that shop's phone number on the storefront gets the credit note but not the discount.
        String typed = placedOrder(2, "0700000080");
        assertThat(salesOrderService.get(TENANT, typed).buyerVerified()).isFalse();
        assertThat(salesOrderService.get(TENANT, typed).lines().get(0).unitPrice()).isEqualByComparingTo("99.50");
    }

    @Test
    void anAccountCannotBeOpenedFromAnOrderThatNamesNoShop() throws Exception {
        makeThisASuppliersShop();
        String typed = placedOrder(2, "0700000081");

        assertStatus(
                () -> salesOrderService.decideAccountForOrder(TENANT, typed,
                        new DecideTradeAccountRequest("approved", null, null, new BigDecimal("10"))),
                HttpStatus.CONFLICT);
        assertStatus(
                () -> salesOrderService.decideAccountForOrder(OTHER_SHOP, typed,
                        new DecideTradeAccountRequest("approved", null, null)),
                HttpStatus.NOT_FOUND);
        assertThat(tradeAccountRepository.count()).isZero();
    }

    // ── Trade depth (P4): a buyer's own prices, standing orders, the supplier's figures ──

    @Test
    void aShopsAccountCanHaveItsOwnPricePerProduct() throws Exception {
        String[] ids = aListedSupplierAndASignedInBuyer(true).split("\\|");
        String first = basketService.submit(BUYER_TOKEN, basket(BUYER_SHOP, ids[0], ids[1], "2")).results().get(0).orderId();
        String accountId = salesOrderService.decideAccountForOrder(TENANT, first,
                new DecideTradeAccountRequest("approved", null, null, new BigDecimal("10"))).id();

        List<TradeAccountPriceDto> saved = tradeAccountService.replacePrices(TENANT, accountId,
                List.of(new TradeAccountPriceDto(publishedItemId, new BigDecimal("70"), null)));
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).itemName()).isEqualTo("Published Item");

        // The agreed price is the price; the 10% discount is not taken off it as well.
        String next = basketService.submit(BUYER_TOKEN, basket(BUYER_SHOP, ids[0], ids[1], "4")).results().get(0).orderId();
        assertThat(salesOrderService.get(TENANT, next).lines().get(0).unitPrice()).isEqualByComparingTo("70.00");
        assertThat(salesOrderService.get(TENANT, next).total()).isEqualByComparingTo("280.00");
        // Typing the shop's phone number on the storefront does not get its prices.
        String typed = placedOrder(2, "0700000080");
        assertThat(salesOrderService.get(TENANT, typed).lines().get(0).unitPrice()).isEqualByComparingTo("99.50");

        // Another shop can neither read nor change the list, and a product from elsewhere is refused.
        assertStatus(() -> tradeAccountService.prices(OTHER_SHOP, accountId), HttpStatus.NOT_FOUND);
        assertStatus(() -> tradeAccountService.replacePrices(OTHER_SHOP, accountId, List.of()), HttpStatus.NOT_FOUND);
        assertStatus(
                () -> tradeAccountService.replacePrices(TENANT, accountId,
                        List.of(new TradeAccountPriceDto(UUID.randomUUID().toString(), BigDecimal.TEN, null))),
                HttpStatus.BAD_REQUEST);
        assertThat(tradeAccountService.prices(TENANT, accountId)).hasSize(1);
    }

    @Test
    void anAccountThatIsOnlyAPhoneNumberGetsNoPriceList() throws Exception {
        makeThisASuppliersShop();
        applyForAccount("0700000091").andExpect(status().isAccepted());
        String accountId = tradeAccountService.list(TENANT, null).get(0).id();

        assertStatus(
                () -> tradeAccountService.replacePrices(TENANT, accountId,
                        List.of(new TradeAccountPriceDto(publishedItemId, new BigDecimal("70"), null))),
                HttpStatus.CONFLICT);
        assertThat(tradeAccountPriceRepository.count()).isZero();
    }

    @Test
    void aStandingOrderPlacesAnOrderOnItsDateAndSaysWhyWhenItCannot() throws Exception {
        aSupplierWithOneConnectedShop();
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Nairobi"));

        StandingOrderResponse weekly = standingOrderService.start(TENANT, BUYER_SHOP, "Buyer Mini Mart", "Mama Njeri",
                "254700000092", 7, List.of(new StandingOrderService.Wanted(publishedItemId, new BigDecimal("3"))), today);
        assertThat(weekly.nextRunOn()).isEqualTo(today.plusDays(7));
        assertThat(weekly.lines().get(0).itemName()).isEqualTo("Published Item");
        // More than the shop will have: it cannot be placed.
        standingOrderService.start(TENANT, BUYER_SHOP, "Buyer Mini Mart", "Mama Njeri",
                "254700000092", 7, List.of(new StandingOrderService.Wanted(publishedItemId, new BigDecimal("500"))), today);

        // Not due yet.
        assertThat(standingOrderService.runDue(today)).isZero();
        assertThat(salesOrderRepository.count()).isZero();

        assertThat(standingOrderService.runDue(today.plusDays(7))).isEqualTo(1);

        List<SalesOrderResponse> placed = salesOrderService.list(TENANT, "placed");
        assertThat(placed).hasSize(1);
        assertThat(placed.get(0).buyerVerified()).isTrue();
        assertThat(placed.get(0).total()).isEqualByComparingTo("298.50");
        List<StandingOrderResponse> mine = standingOrderService.forBuyer(BUYER_SHOP);
        assertThat(mine).hasSize(2);
        assertThat(mine).allMatch(o -> o.nextRunOn().equals(today.plusDays(14)));
        assertThat(mine).extracting(StandingOrderResponse::lastResult)
                .anyMatch(r -> r.startsWith("Order placed"))
                .anyMatch(r -> r.startsWith("Not placed"));
        // The same day does not place it twice.
        assertThat(standingOrderService.runDue(today.plusDays(7))).isZero();
    }

    @Test
    void aStandingOrderIsStoppedByEitherSideAndByNobodyElse() throws Exception {
        aSupplierWithOneConnectedShop();
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Nairobi"));
        String id = standingOrderService.start(TENANT, BUYER_SHOP, "Buyer Mini Mart", "Mama Njeri",
                "254700000093", 7, List.of(new StandingOrderService.Wanted(publishedItemId, BigDecimal.ONE)), today).id();

        assertThat(standingOrderService.forSupplier(TENANT)).hasSize(1);
        assertThat(standingOrderService.forSupplier(STRANGER_SHOP)).isEmpty();
        assertThat(standingOrderService.forBuyer(STRANGER_SHOP)).isEmpty();
        assertStatus(() -> standingOrderService.stopAsBuyer(STRANGER_SHOP, id), HttpStatus.NOT_FOUND);
        assertStatus(() -> standingOrderService.stopAsSupplier(STRANGER_SHOP, id), HttpStatus.NOT_FOUND);
        // A product the supplier does not sell cannot be put on a standing order.
        assertStatus(
                () -> standingOrderService.start(TENANT, BUYER_SHOP, "Buyer Mini Mart", "Mama Njeri", "254700000093", 7,
                        List.of(new StandingOrderService.Wanted(UUID.randomUUID().toString(), BigDecimal.ONE)), today),
                HttpStatus.BAD_REQUEST);
        assertThat(standingOrderRepository.count()).isEqualTo(1);

        standingOrderService.stopAsBuyer(BUYER_SHOP, id);

        assertThat(standingOrderService.forSupplier(TENANT)).isEmpty();
        assertThat(standingOrderService.runDue(today.plusDays(30))).isZero();
        assertThat(salesOrderRepository.count()).isZero();
    }

    @Test
    void theSuppliersFiguresComeFromItsOwnOrdersAndInvoices() throws Exception {
        makeThisASuppliersShop();
        String orderId = acceptedOrder(10, "4", "0700000094");
        salesOrderService.dispatch(TENANT, orderId);
        placedOrder(2, "0700000095");

        SalesFiguresResponse figures = salesFiguresService.figures(TENANT, 30);

        assertThat(figures.orders()).isEqualTo(2);
        assertThat(figures.waitingForAnAnswer()).isEqualTo(1);
        assertThat(figures.askedValue()).isEqualByComparingTo("1194.00");
        assertThat(figures.acceptedValue()).isEqualByComparingTo("398.00");
        // Of what was answered: 4 of 10.
        assertThat(figures.fillRatePercent()).isEqualByComparingTo("40.0");
        assertThat(figures.invoiced()).isEqualByComparingTo("398.00");
        assertThat(figures.stillOwed()).isEqualByComparingTo("398.00");
        assertThat(figures.topProducts().get(0).name()).isEqualTo("Published Item");
        assertThat(figures.topProducts().get(0).quantity()).isEqualByComparingTo("4");
        assertThat(figures.topBuyers().get(0).name()).isEqualTo("Njeri Kiosk");

        SalesFiguresResponse nobody = salesFiguresService.figures(OTHER_SHOP, 30);
        assertThat(nobody.orders()).isZero();
        assertThat(nobody.invoiced()).isEqualByComparingTo("0");
    }

    // ── Supplier engagement must cost the order path nothing (SUPPLIER_ENGAGEMENT_DESIGN.md §7) ──────────

    @Autowired
    private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    @Autowired
    private zelisline.ub.wholesale.application.SupplierOverviewService supplierOverviewService;

    @Autowired
    private zelisline.ub.wholesale.application.SupplierStandingService supplierStandingService;

    @Autowired
    private zelisline.ub.wholesale.application.SupplierReadinessService supplierReadinessService;

    private static final List<String> ENGAGEMENT_FLAGS = List.of(
            "supplier_engagement",
            "supplier_engagement.readiness",
            "supplier_engagement.milestones",
            "supplier_engagement.standing",
            "supplier_engagement.buyer_lines",
            "supplier_engagement.contribution",
            "supplier_engagement.notifications");

    /** Writes the flags where the shop keeps them: {@code featureFlags} in its settings. */
    private void setEngagementFlags(boolean on) throws Exception {
        var business = businessRepository.findById(TENANT).orElseThrow();
        String settings = business.getSettings() == null || business.getSettings().isBlank()
                ? "{}"
                : business.getSettings();
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(settings);
        var flags = root.has("featureFlags") && root.get("featureFlags").isObject()
                ? (com.fasterxml.jackson.databind.node.ObjectNode) root.get("featureFlags")
                : root.putObject("featureFlags");
        for (String flag : ENGAGEMENT_FLAGS) {
            flags.put(flag, on);
        }
        business.setSettings(objectMapper.writeValueAsString(root));
        businessRepository.save(business);
    }

    /** How many SQL statements a piece of work prepares. */
    private long statements(Runnable work) {
        var statistics = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        work.run();
        return statistics.getPrepareStatementCount();
    }

    @Test
    void engagementAddsNoQueryToAnsweringOrDispatchingAnOrder() throws Exception {
        makeThisASuppliersShop();
        // One whole order first, so one-off work (first invoice, first stock pick) is not what gets measured.
        String warmUp = placedOrder(12, "0700000070");
        salesOrderService.answer(TENANT, warmUp, supplying(warmUp, "12"));
        salesOrderService.dispatch(TENANT, warmUp);

        String without = placedOrder(12, "0700000071");
        String with = placedOrder(12, "0700000072");
        AnswerSalesOrderRequest answerWithout = supplying(without, "12");
        AnswerSalesOrderRequest answerWith = supplying(with, "12");

        setEngagementFlags(false);
        long answerOff = statements(() -> salesOrderService.answer(TENANT, without, answerWithout));
        long dispatchOff = statements(() -> salesOrderService.dispatch(TENANT, without));

        setEngagementFlags(true);
        long answerOn = statements(() -> salesOrderService.answer(TENANT, with, answerWith));
        long dispatchOn = statements(() -> salesOrderService.dispatch(TENANT, with));

        // Every engagement flag on, and the order path runs exactly the statements it ran with them all off.
        assertThat(answerOn).isEqualTo(answerOff);
        assertThat(dispatchOn).isEqualTo(dispatchOff);
    }

    @Test
    void withTheFlagsOffEveryEngagementAnswerIsTheOffAnswer() throws Exception {
        makeThisASuppliersShop();
        String orderId = placedOrder(12, "0700000073");
        salesOrderService.answer(TENANT, orderId, supplying(orderId, "12"));
        salesOrderService.dispatch(TENANT, orderId);
        setEngagementFlags(false);

        assertThat(supplierOverviewService.overview(TENANT, "anyone"))
                .isEqualTo(zelisline.ub.wholesale.api.dto.SupplierOverviewResponse.off());
        assertThat(supplierStandingService.standing(TENANT, 90))
                .isEqualTo(zelisline.ub.wholesale.api.dto.SupplierStandingResponse.off());
        assertThat(supplierStandingService.buyerLines(TENANT))
                .isEqualTo(zelisline.ub.wholesale.api.dto.BuyerLinesResponse.off());
        assertThat(supplierReadinessService.readiness(TENANT))
                .isEqualTo(zelisline.ub.wholesale.api.dto.SupplierReadinessResponse.off());

        // The figures page carries no answer-time fields at all, so what it sends is what it sent before.
        String figures = objectMapper.writeValueAsString(salesFiguresService.figures(TENANT, 30));
        assertThat(figures).doesNotContain("answeredSample").doesNotContain("medianAnswerWorkingHours");
    }
}
