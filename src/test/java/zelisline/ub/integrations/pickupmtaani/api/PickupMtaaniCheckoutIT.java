package zelisline.ub.integrations.pickupmtaani.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;

import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.domain.Category;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient.DeliveryCharge;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.pricing.domain.SellingPrice;
import zelisline.ub.pricing.repository.SellingPriceRepository;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.storefront.domain.WebOrderShipment;
import zelisline.ub.storefront.repository.WebCartLineRepository;
import zelisline.ub.storefront.repository.WebCartRepository;
import zelisline.ub.storefront.repository.WebOrderLineRepository;
import zelisline.ub.storefront.repository.WebOrderRepository;
import zelisline.ub.storefront.repository.WebOrderShipmentRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PickupMtaaniCheckoutIT {

    private static final String TENANT = "ffffffff-ffff-ffff-ffff-ffffffffff0a";
    private static final String SLUG = "pum-checkout-it";
    private static final String API_KEY = "tenant-pum-key";
    private static final long ORIGIN = 362L;
    private static final long DEST_AGENT = 454L;

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
    private WebOrderRepository webOrderRepository;
    @Autowired
    private WebOrderLineRepository webOrderLineRepository;
    @Autowired
    private WebOrderShipmentRepository webOrderShipmentRepository;
    @Autowired
    private CatalogBootstrapService catalogBootstrapService;
    @Autowired
    private ItemCatalogService itemCatalogService;
    @Autowired
    private CredentialEncryptionService credentialEncryptionService;

    @MockitoBean
    @SuppressWarnings("unused")
    private PickupMtaaniClient pickupMtaaniClient;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private String pricedItemId;

    @BeforeEach
    void seed() {
        webOrderShipmentRepository.deleteAll();
        webOrderLineRepository.deleteAll();
        webOrderRepository.deleteAll();
        webCartLineRepository.deleteAll();
        webCartRepository.deleteAll();
        sellingPriceRepository.deleteAll();
        inventoryBatchRepository.deleteAll();
        itemRepository.deleteAll();
        categoryRepository.deleteAll();
        itemTypeRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("PUM Checkout Co");
        b.setSlug(SLUG);
        b.setCurrency("KES");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Catalog Branch");
        br.setActive(true);
        String branchId = branchRepository.save(br).getId();

        String enc = credentialEncryptionService.encryptSecret(API_KEY);
        b.setSettings("""
                {"storefront":{"enabled":true,"catalogBranchId":"%s","label":"Hi"},
                 "pickupMtaani":{"enabled":true,"apiKeyEnc":"%s","accountMode":"single_business",
                   "originAgentId":%d,"originAgentName":"Kahawa West Agent","agent":true,"doorstep":true,
                   "feeMode":"pass_through","markupKes":0}}
                """.formatted(branchId, enc, ORIGIN));
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
                                null, null, null, null, null, null, null, null, null, null, false, null, null, null, null),
                        null)
                .body()
                .id();
        Item row = itemRepository.findById(pricedItemId).orElseThrow();
        row.setWebPublished(true);
        itemRepository.save(row);

        SellingPrice sp = new SellingPrice();
        sp.setBusinessId(TENANT);
        sp.setItemId(pricedItemId);
        sp.setBranchId(branchId);
        sp.setPrice(new BigDecimal("10.00"));
        sp.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        sellingPriceRepository.save(sp);

        InventoryBatch batch = new InventoryBatch();
        batch.setBusinessId(TENANT);
        batch.setBranchId(branchId);
        batch.setItemId(pricedItemId);
        batch.setBatchNumber("CO-1");
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

    private String createCart() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/public/businesses/" + SLUG + "/carts"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText();
    }

    private void addLine(String cartId, int qty) throws Exception {
        mockMvc.perform(post("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId + "/lines")
                        .contentType(APPLICATION_JSON)
                        .content("{\"itemId\":\"%s\",\"quantity\":%d}".formatted(pricedItemId, qty)))
                .andExpect(status().isOk());
    }

    private void completeContact(String cartId) throws Exception {
        mockMvc.perform(patch("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId + "/checkout-state/contact")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"firstName":"Guest","lastName":"Shopper","email":"guest@example.com",
                                 "areaCode":"+254","phone":"700111222"}
                                """))
                .andExpect(status().isOk());
    }

    private String quote(String mode, long destinationId) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/public/businesses/" + SLUG + "/pickup-mtaani/quote")
                        .contentType(APPLICATION_JSON)
                        .content("{\"mode\":\"%s\",\"destinationId\":%d}".formatted(mode, destinationId)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(r.getResponse().getContentAsString(), "$.quoteId");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder delivery(
            String cartId, String fulfillmentJson) throws Exception {
        String body = """
                {"county":"Nairobi","subCounty":"Roysambu","ward":"Githurai","streetAddress":"35393",
                 "deliveryNotes":"","saveForNextTime":false%s}
                """.formatted(fulfillmentJson == null ? "" : ",\"fulfillment\":" + fulfillmentJson);
        return patch("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId + "/checkout-state/delivery")
                .contentType(APPLICATION_JSON)
                .content(body);
    }

    private MvcResult checkout(String cartId) throws Exception {
        return mockMvc.perform(post("/api/v1/public/businesses/" + SLUG + "/carts/" + cartId + "/checkout")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"customerName":"Ada","customerPhone":"0700456789","customerEmail":"ada@test.invalid"}
                                """))
                .andReturn();
    }

    @Test
    void checkout_withPickupMtaani_addsFeeAndWritesPendingShipment() throws Exception {
        when(pickupMtaaniClient.getAgentDeliveryCharge(API_KEY, ORIGIN, DEST_AGENT))
                .thenReturn(new DeliveryCharge(new BigDecimal("150.00"), "{}"));

        String cartId = createCart();
        addLine(cartId, 3);
        completeContact(cartId);
        String quoteId = quote("agent", DEST_AGENT);

        mockMvc.perform(delivery(cartId, """
                {"carrier":"pickup_mtaani","mode":"agent","destinationId":%d,
                 "destinationLabel":"Westlands Agent","quoteId":"%s"}
                """.formatted(DEST_AGENT, quoteId)))
                .andExpect(status().isOk());

        MvcResult res = checkout(cartId);

        assertThat(res.getResponse().getStatus()).isEqualTo(201);
        String orderId = JsonPath.read(res.getResponse().getContentAsString(), "$.orderId");
        assertThat((Double) JsonPath.read(res.getResponse().getContentAsString(), "$.grandTotal"))
                .isEqualTo(180.00);

        WebOrderShipment shipment = webOrderShipmentRepository.findByWebOrderId(orderId).orElseThrow();
        assertThat(shipment.getCarrier()).isEqualTo(WebOrderShipment.CARRIER_PICKUP_MTAANI);
        assertThat(shipment.getBookStatus()).isEqualTo(WebOrderShipment.BOOK_PENDING);
        assertThat(shipment.getMode()).isEqualTo("agent");
        assertThat(shipment.getOriginAgentId()).isEqualTo(ORIGIN);
        assertThat(shipment.getDestinationAgentId()).isEqualTo(DEST_AGENT);
        assertThat(shipment.getShopperFeeKes()).isEqualByComparingTo("150.00");
        assertThat(shipment.getQuotedFeeKes()).isEqualByComparingTo("150.00");
        assertThat(shipment.getPackageValueKes()).isEqualTo(30);
    }

    @Test
    void checkout_withoutPickupMtaani_noShipmentAndBaseTotal() throws Exception {
        String cartId = createCart();
        addLine(cartId, 3);
        completeContact(cartId);

        mockMvc.perform(delivery(cartId, null)).andExpect(status().isOk());

        MvcResult res = checkout(cartId);
        assertThat(res.getResponse().getStatus()).isEqualTo(201);
        assertThat((Double) JsonPath.read(res.getResponse().getContentAsString(), "$.grandTotal"))
                .isEqualTo(30.00);
        assertThat(webOrderShipmentRepository.count()).isZero();
    }

    @Test
    void delivery_quoteDestinationMismatch_rejected() throws Exception {
        when(pickupMtaaniClient.getAgentDeliveryCharge(API_KEY, ORIGIN, DEST_AGENT))
                .thenReturn(new DeliveryCharge(new BigDecimal("150.00"), "{}"));

        String cartId = createCart();
        addLine(cartId, 1);
        completeContact(cartId);
        String quoteId = quote("agent", DEST_AGENT);

        mockMvc.perform(delivery(cartId, """
                {"carrier":"pickup_mtaani","mode":"agent","destinationId":999,"quoteId":"%s"}
                """.formatted(quoteId)))
                .andExpect(status().isBadRequest());
    }
}
