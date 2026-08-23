package zelisline.ub.storefront.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.math.BigDecimal;
import java.time.Instant;
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

import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.domain.StockMovement;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.purchasing.repository.StockMovementRepository;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.storefront.WebOrderChannels;
import zelisline.ub.storefront.WebOrderStatuses;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.repository.WebOrderRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * WhatsApp order expiry (scope §11): the sweeper marks unconfirmed orders
 * expired and reverses the sale movement at the batch level.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class WhatsAppOrderExpiryIT {

    private static final String TENANT = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String SLUG = "wa-expiry-it";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private ItemTypeRepository itemTypeRepository;

    @Autowired
    private CatalogBootstrapService catalogBootstrapService;

    @Autowired
    private ItemCatalogService itemCatalogService;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private InventoryBatchRepository inventoryBatchRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private WebOrderRepository webOrderRepository;

    @Autowired
    private WhatsAppOrderExpiryService expiryService;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private String branchId;
    private String itemId;
    private String batchId;
    private String orderId;

    @BeforeEach
    void seed() {
        stockMovementRepository.deleteAll();
        inventoryBatchRepository.deleteAll();
        webOrderRepository.deleteAll();
        itemRepository.deleteAll();
        itemTypeRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Expiry Shop");
        b.setSlug(SLUG);
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Main Branch");
        br.setActive(true);
        branchId = branchRepository.save(br).getId();

        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT);
        String typeId = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT).getFirst().getId();
        itemId = itemCatalogService.createItem(
                TENANT,
                new zelisline.ub.catalog.api.dto.CreateItemRequest(
                        "SKU-EXP", null, "Expiry Item", null, typeId, null, null, null,
                        false, true, true,
                        null, null, null, null, null, null, null, null, null, null, false, null, null, null, null),
                null
        ).body().id();

        InventoryBatch batch = new InventoryBatch();
        batch.setBusinessId(TENANT);
        batch.setBranchId(branchId);
        batch.setItemId(itemId);
        batch.setBatchNumber("EXP-BATCH-1");
        batch.setSourceType("test");
        batch.setSourceId(UUID.randomUUID().toString());
        batch.setInitialQuantity(new BigDecimal("10.0000"));
        batch.setQuantityRemaining(new BigDecimal("7.0000")); // 3 sold below
        batch.setUnitCost(new BigDecimal("2.0000"));
        batch.setReceivedAt(Instant.parse("2026-01-01T12:00:00Z"));
        batch.setStatus(InventoryConstants.BATCH_STATUS_ACTIVE);
        batchId = inventoryBatchRepository.save(batch).getId();

        WebOrder order = new WebOrder();
        order.setBusinessId(TENANT);
        order.setCartId(UUID.randomUUID().toString());
        order.setCatalogBranchId(branchId);
        order.setStatus(WebOrderStatuses.PENDING_PAYMENT);
        order.setChannel(WebOrderChannels.WHATSAPP);
        order.setCurrency("KES");
        order.setGrandTotal(new BigDecimal("300.00"));
        order.setCustomerName("Wanjiru");
        order.setCustomerPhone("0711222333");
        order.setExpiresAt(Instant.now().minusSeconds(3600));
        webOrderRepository.save(order);
        orderId = order.getId();

        StockMovement sale = new StockMovement();
        sale.setBusinessId(TENANT);
        sale.setBranchId(branchId);
        sale.setItemId(itemId);
        sale.setBatchId(batchId);
        sale.setMovementType(InventoryConstants.MOVEMENT_SALE);
        sale.setReferenceType(SalesConstants.STOCK_REFERENCE_TYPE_WEB_ORDER);
        sale.setReferenceId(orderId);
        sale.setQuantityDelta(new BigDecimal("-3.0000"));
        sale.setUnitCost(new BigDecimal("2.0000"));
        sale.setNotes("WhatsApp order checkout");
        stockMovementRepository.save(sale);
    }

    @Test
    void sweep_marksOrderExpiredAndReleasesStock() {
        int released = expiryService.sweepExpired();

        assertEquals(1, released);
        WebOrder order = webOrderRepository.findById(orderId).orElseThrow();
        assertEquals("expired", order.getHandoffState());

        InventoryBatch batch = inventoryBatchRepository.findById(batchId).orElseThrow();
        assertEquals(0, new BigDecimal("10.0000").compareTo(batch.getQuantityRemaining()));

        long releaseMovements = stockMovementRepository
                .findByBusinessIdAndReferenceTypeAndReferenceId(TENANT, SalesConstants.STOCK_REFERENCE_TYPE_WEB_ORDER, orderId)
                .stream()
                .filter(m -> InventoryConstants.MOVEMENT_WEB_ORDER_EXPIRY.equals(m.getMovementType()))
                .count();
        assertEquals(1, releaseMovements);
    }

    @Test
    void sweep_skipsAlreadyExpiredOrders() {
        WebOrder order = webOrderRepository.findById(orderId).orElseThrow();
        order.setHandoffState("expired");
        webOrderRepository.save(order);

        assertEquals(0, expiryService.sweepExpired());
    }

    @Test
    void sweep_skipsConfirmedOrders() {
        WebOrder order = webOrderRepository.findById(orderId).orElseThrow();
        order.setFulfillmentStatus("confirmed");
        webOrderRepository.save(order);

        assertEquals(0, expiryService.sweepExpired());
        assertNotNull(order.getExpiresAt());
    }
}
