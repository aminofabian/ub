package zelisline.ub.desktop.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.desktop.api.dto.CloudSalesSnapshot;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.pricing.repository.TaxRateRepository;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.sales.repository.SalePaymentRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.suppliers.repository.SupplierContactRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Cloud → desktop "down" sync: sales made in the web portal / other tills are
 * mirrored into the local database, idempotently (by sale id) and without
 * tripping the local NOT NULL / unique constraints (receipt numbers, shift and
 * user FKs).
 */
class DesktopSyncPullServiceTest {

    private static final String LOCAL_BUSINESS = "local-biz";
    private static final String CLOUD_ORIGIN = "https://shop.example.com";
    private static final String STAFF_ID = "staff-1";
    private static final String BRANCH_ID = "branch-1";

    private final BusinessRepository businessRepository = mock(BusinessRepository.class);
    private final BranchRepository branchRepository = mock(BranchRepository.class);
    private final CategoryRepository categoryRepository = mock(CategoryRepository.class);
    private final ItemRepository itemRepository = mock(ItemRepository.class);
    private final ItemTypeRepository itemTypeRepository = mock(ItemTypeRepository.class);
    private final TaxRateRepository taxRateRepository = mock(TaxRateRepository.class);
    private final DesktopStaffSyncService staffSyncService = mock(DesktopStaffSyncService.class);
    private final DesktopMediaSyncService mediaSyncService = mock(DesktopMediaSyncService.class);
    private final CloudSyncSession cloudSyncSession = mock(CloudSyncSession.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final DesktopSyncProgressService syncProgress = mock(DesktopSyncProgressService.class);
    private final SaleRepository saleRepository = mock(SaleRepository.class);
    private final SaleItemRepository saleItemRepository = mock(SaleItemRepository.class);
    private final SalePaymentRepository salePaymentRepository = mock(SalePaymentRepository.class);
    private final ShiftRepository shiftRepository = mock(ShiftRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final CustomerRepository customerRepository = mock(CustomerRepository.class);
    private final CustomerPhoneRepository customerPhoneRepository = mock(CustomerPhoneRepository.class);
    private final CreditAccountRepository creditAccountRepository = mock(CreditAccountRepository.class);
    private final SupplierRepository supplierRepository = mock(SupplierRepository.class);
    private final SupplierContactRepository supplierContactRepository = mock(SupplierContactRepository.class);
    private final zelisline.ub.purchasing.repository.RawPurchaseSessionRepository rawPurchaseSessionRepository =
        mock(zelisline.ub.purchasing.repository.RawPurchaseSessionRepository.class);
    private final zelisline.ub.purchasing.repository.RawPurchaseLineRepository rawPurchaseLineRepository =
        mock(zelisline.ub.purchasing.repository.RawPurchaseLineRepository.class);
    private final zelisline.ub.purchasing.repository.SupplierInvoiceRepository supplierInvoiceRepository =
        mock(zelisline.ub.purchasing.repository.SupplierInvoiceRepository.class);
    private final zelisline.ub.purchasing.repository.SupplierInvoiceLineRepository supplierInvoiceLineRepository =
        mock(zelisline.ub.purchasing.repository.SupplierInvoiceLineRepository.class);
    private final zelisline.ub.storefront.repository.WebOrderRepository webOrderRepository =
        mock(zelisline.ub.storefront.repository.WebOrderRepository.class);
    private final zelisline.ub.storefront.repository.WebOrderLineRepository webOrderLineRepository =
        mock(zelisline.ub.storefront.repository.WebOrderLineRepository.class);

    private final RestClient.Builder restClientBuilder = RestClient.builder();
    private final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    private MockRestServiceServer server;

    private DesktopSyncPullService service;

    @BeforeEach
    void setUp() {
        // The service wraps its writes in a TransactionTemplate; run the callback
        // inline so the mocked repos see the inserts.
        when(transactionTemplate.execute(org.mockito.ArgumentMatchers.any(TransactionCallback.class))).thenAnswer(inv ->
            inv.getArgument(0, TransactionCallback.class)
                .doInTransaction(mock(TransactionStatus.class)));

        when(cloudSyncSession.load()).thenReturn(Optional.of(new CloudSyncSession.Session(
            CLOUD_ORIGIN, "cloud-biz", "access-token", "refresh-token",
            "owner-id", List.of(STAFF_ID), null, null, null, null)));
        when(userRepository.findIdsByBusinessIdAndDeletedAtIsNull(LOCAL_BUSINESS))
            .thenReturn(List.of(STAFF_ID));
        when(userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(STAFF_ID, LOCAL_BUSINESS))
            .thenReturn(Optional.of(mock(User.class)));
        when(branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(BRANCH_ID, LOCAL_BUSINESS))
            .thenReturn(Optional.of(mock(Branch.class)));
        when(itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull("item-1", LOCAL_BUSINESS))
            .thenReturn(Optional.of(mock(Item.class)));
        when(customerRepository.findByIdAndBusinessIdAndDeletedAtIsNull("customer-1", LOCAL_BUSINESS))
            .thenReturn(Optional.of(mock(Customer.class)));

        service = new DesktopSyncPullService(
            businessRepository, branchRepository, categoryRepository, itemRepository,
            itemTypeRepository, taxRateRepository, staffSyncService, mediaSyncService,
            cloudSyncSession, transactionTemplate, syncProgress, saleRepository,
            saleItemRepository, salePaymentRepository, shiftRepository, userRepository,
            supplierRepository, supplierContactRepository, customerRepository,
            customerPhoneRepository, creditAccountRepository, rawPurchaseSessionRepository,
            rawPurchaseLineRepository, supplierInvoiceRepository, supplierInvoiceLineRepository,
            webOrderRepository, webOrderLineRepository, restClientBuilder);
        ReflectionTestUtils.setField(service, "desktopBusinessId", LOCAL_BUSINESS);

        server = MockRestServiceServer.bindTo(restClientBuilder).build();
    }

    private static CloudSalesSnapshot.CloudSaleData cloudSale(String id, long receiptNo) {
        return new CloudSalesSnapshot.CloudSaleData(
            id,
            BRANCH_ID,
            "cloud-shift-1",
            Instant.parse("2026-08-20T08:00:00Z"),
            "completed",
            "idem-" + id,
            new BigDecimal("1500.00"),
            new BigDecimal("1500.00"),
            STAFF_ID,
            "customer-1",
            Instant.parse("2026-08-20T10:00:00Z"),
            null,
            null,
            BigDecimal.ZERO,
            receiptNo,
            List.of(new CloudSalesSnapshot.CloudSaleItemData(
                "line-1", 0, "ITEM", "Tusker 500ml", "item-1",
                new BigDecimal("1"), new BigDecimal("1500.00"), new BigDecimal("1500.00"),
                new BigDecimal("1200.00"), new BigDecimal("1200.00"), new BigDecimal("300.00"),
                null, null, null, null)),
            List.of(new CloudSalesSnapshot.CloudSalePaymentData(
                "pay-1", "CASH", new BigDecimal("1500.00"), null, 0))
        );
    }

    private void expectSalesRequest(String since, String json) {
        server.expect(requestTo(CLOUD_ORIGIN + "/api/v1/desktop/sync/sales?since=" + since))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer access-token"))
            .andExpect(header("X-Tenant-Id", "cloud-biz"))
            .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private String snapshotJson(List<CloudSalesSnapshot.CloudSaleData> sales) throws Exception {
        return objectMapper.writeValueAsString(new CloudSalesSnapshot(sales, null));
    }

    @Test
    void mergeBusinessSettingsKeepsLocalDesktopNodeAndAdoptsCloudSettings() throws Exception {
        String local = "{\"theme\":\"dark\",\"desktop\":{\"licenseKey\":\"tok-1\"}}";
        String cloud = "{\"theme\":\"light\",\"onboarding\":{\"done\":true}}";

        String merged = DesktopSyncPullService.mergeBusinessSettings(local, cloud);

        ObjectNode root = (ObjectNode) new ObjectMapper().readTree(merged);
        assertEquals("light", root.path("theme").asText(), "cloud settings win outside the desktop node");
        assertTrue(root.path("onboarding").path("done").asBoolean());
        assertEquals("tok-1", root.path("desktop").path("licenseKey").asText(),
            "the local desktop node (license key) must survive a master-data pull");
    }

    @Test
    void storeCloudPlanStampsTierAndStatusIntoDesktopNode() throws Exception {
        String merged = DesktopSyncPullService.storeCloudPlan(
            "{\"theme\":\"dark\"}", "Growth", "ACTIVE");

        ObjectNode root = (ObjectNode) new ObjectMapper().readTree(merged);
        assertEquals("growth", root.path("desktop").path("cloudPlanTier").asText());
        assertEquals("ACTIVE", root.path("desktop").path("cloudPlanStatus").asText());
        assertEquals("dark", root.path("theme").asText());
    }

    @Test
    void mirrorsCloudSalesIntoLocalDatabase() throws Exception {
        String json = snapshotJson(List.of(cloudSale("sale-1", 1L)));
        expectSalesRequest("1970-01-01T00:00:00Z", json);

        int inserted = service.pullCloudSales();

        server.verify();
        assertEquals(1, inserted);

        // The sale is stamped synced so it is never pushed back up.
        verify(saleRepository).save(org.mockito.ArgumentMatchers.argThat(sale ->
            "sale-1".equals(sale.getId()) && sale.getCloudSyncedAt() != null));
        verify(saleItemRepository).save(any());
        verify(salePaymentRepository).save(any());

        // The placeholder shift is closed AND stamped synced so the push side
        // never uploads it (its zero cash figures must not reach the cloud).
        verify(shiftRepository).save(org.mockito.ArgumentMatchers.argThat(shift ->
            "cloud-shift-1".equals(shift.getId())
                && shift.getStatus() != null
                && shift.getCloudSyncedAt() != null
                && STAFF_ID.equals(shift.getOpenedBy())));

        // The cursor advances to the newest sale seen and is persisted.
        verify(cloudSyncSession).persistLastSalesPullAt(
            any(), org.mockito.ArgumentMatchers.eq(Instant.parse("2026-08-20T10:00:00Z")));
    }

    @Test
    void skipsSalesAlreadyPresentLocallyButStillAdvancesCursor() throws Exception {
        when(saleRepository.findByIdAndBusinessId("sale-1", LOCAL_BUSINESS))
            .thenReturn(Optional.of(mock(Sale.class)));
        String json = snapshotJson(List.of(cloudSale("sale-1", 1L)));
        expectSalesRequest("1970-01-01T00:00:00Z", json);

        int inserted = service.pullCloudSales();

        server.verify();
        assertEquals(0, inserted);
        verify(saleRepository, never()).save(any());
        verify(cloudSyncSession).persistLastSalesPullAt(
            any(), org.mockito.ArgumentMatchers.eq(Instant.parse("2026-08-20T10:00:00Z")));
    }

    @Test
    void collidingReceiptNumberLeavesReceiptBlankInsteadOfFailingTheBatch() throws Exception {
        // The till already sold receipt 1 locally; the cloud independently
        // allocated 1 too. The unique key (business_id, receipt_no) would roll
        // back the whole pull — the sale must still be mirrored, receipt-less.
        when(saleRepository.existsByBusinessIdAndReceiptNo(LOCAL_BUSINESS, 1L))
            .thenReturn(true);
        String json = snapshotJson(List.of(cloudSale("sale-1", 1L)));
        expectSalesRequest("1970-01-01T00:00:00Z", json);

        int inserted = service.pullCloudSales();

        server.verify();
        assertEquals(1, inserted);
        verify(saleRepository).save(org.mockito.ArgumentMatchers.argThat(sale ->
            "sale-1".equals(sale.getId()) && sale.getReceiptNo() == null));
    }

    @Test
    void unknownCloudCashierFallsBackToAnyLocalUser() throws Exception {
        // staff-1 isn't mirrored locally yet (new hire after the last master
        // pull): the sale is attributed to another local user instead of
        // tripping the sold_by FK.
        when(userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(STAFF_ID, LOCAL_BUSINESS))
            .thenReturn(Optional.empty());
        String json = snapshotJson(List.of(cloudSale("sale-1", 1L)));
        expectSalesRequest("1970-01-01T00:00:00Z", json);

        int inserted = service.pullCloudSales();

        server.verify();
        assertEquals(1, inserted);
        verify(saleRepository).save(org.mockito.ArgumentMatchers.argThat(sale ->
            STAFF_ID.equals(sale.getSoldBy())));
    }

    @Test
    void saleAtUnsyncedBranchFailsLoudlyAndDoesNotAdvanceTheCursor() throws Exception {
        when(branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(BRANCH_ID, LOCAL_BUSINESS))
            .thenReturn(Optional.empty());
        String json = snapshotJson(List.of(cloudSale("sale-1", 1L)));
        expectSalesRequest("1970-01-01T00:00:00Z", json);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.pullCloudSales());

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(cloudSyncSession, never()).persistLastSalesPullAt(any(), any());
    }

    @Test
    void notConnectedThrowsConflict() {
        when(cloudSyncSession.load()).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.pullCloudSales());

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void pullsInPagesUntilAShortPage() throws Exception {
        // A till that was offline for a while has 501 cloud sales: the first
        // page is full (500), so the pull keeps going for a second page.
        List<CloudSalesSnapshot.CloudSaleData> pageOne = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            pageOne.add(cloudSale("sale-" + i, 1000L + i));
        }
        expectSalesRequest("1970-01-01T00:00:00Z", snapshotJson(pageOne));
        expectSalesRequest("2026-08-20T10:00:00Z", snapshotJson(List.of(cloudSale("sale-500", 1500L))));

        int inserted = service.pullCloudSales();

        server.verify();
        assertEquals(501, inserted);
    }

    @Test
    void noCloudSalesIsAQuietNoOp() throws Exception {
        expectSalesRequest("1970-01-01T00:00:00Z", "{\"sales\":[],\"customers\":[]}");

        int inserted = service.pullCloudSales();

        server.verify();
        assertEquals(0, inserted);
        verify(saleRepository, never()).save(any());
    }

    @Test
    void mirrorsCloudCustomersWithTheirCreditState() throws Exception {
        // The cloud customer directory (phones + live credit account) rides the
        // sales pull, and pulled sales keep their customer reference.
        String json = """
            {"sales":[{"id":"sale-1","branchId":"%s","shiftId":"cloud-shift-1","shiftOpenedAt":"2026-08-20T08:00:00Z","status":"completed","idempotencyKey":"idem-sale-1","grandTotal":1500.00,"cashReceived":1500.00,"soldBy":"%s","customerId":"customer-1","soldAt":"2026-08-20T10:00:00Z","voidedAt":null,"voidNotes":null,"refundedTotal":0.00,"receiptNo":1,"items":[],"payments":[{"id":"pay-1","method":"CASH","amount":1500.00,"reference":null,"sortOrder":0}]}],
             "customers":[{"id":"customer-1","name":"Jane Doe","email":"jane@example.com","notes":null,"phones":[{"id":"phone-1","phone":"254700111222","primary":true}],"creditAccount":{"balanceOwed":500.00,"walletBalance":0,"loyaltyPoints":12,"creditLimit":5000.00}}]}
            """.formatted(BRANCH_ID, STAFF_ID);
        expectSalesRequest("1970-01-01T00:00:00Z", json);
        when(customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc("customer-1"))
            .thenReturn(List.of());
        when(customerPhoneRepository.existsByBusinessIdAndPhone(LOCAL_BUSINESS, "254700111222"))
            .thenReturn(false);
        when(creditAccountRepository.findByCustomerIdAndBusinessId("customer-1", LOCAL_BUSINESS))
            .thenReturn(Optional.empty());
        // Upsert path sees no local row (creates one); the sale's FK guard runs
        // after the mirror and finds it present.
        java.util.concurrent.atomic.AtomicInteger lookups = new java.util.concurrent.atomic.AtomicInteger();
        when(customerRepository.findByIdAndBusinessIdAndDeletedAtIsNull("customer-1", LOCAL_BUSINESS))
            .thenAnswer(inv -> lookups.incrementAndGet() == 1
                ? Optional.empty()
                : Optional.of(mock(Customer.class)));

        int inserted = service.pullCloudSales();

        server.verify();
        assertEquals(1, inserted);
        // Customer is mirrored and stamped synced so it is never pushed back up.
        verify(customerRepository).saveAndFlush(org.mockito.ArgumentMatchers.argThat(c ->
            "customer-1".equals(c.getId())
                && "Jane Doe".equals(c.getName())
                && c.getCloudSyncedAt() != null));
        verify(customerPhoneRepository).save(org.mockito.ArgumentMatchers.argThat(p ->
            "254700111222".equals(p.getPhone()) && p.isPrimary()));
        // The cloud's balance is adopted as-is on the till's copy.
        verify(creditAccountRepository).save(org.mockito.ArgumentMatchers.argThat(a ->
            new BigDecimal("500.00").compareTo(a.getBalanceOwed()) == 0));
        // The pulled sale keeps its customer reference (FK resolves after mirror).
        verify(saleRepository).save(org.mockito.ArgumentMatchers.argThat(sale ->
            "customer-1".equals(sale.getCustomerId())));
    }
}
