package zelisline.ub.desktop.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.desktop.api.dto.ShiftSyncAck;
import zelisline.ub.desktop.application.DesktopSyncPushService.SyncPushResult;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.sales.repository.SalePaymentRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.suppliers.repository.SupplierContactRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;

/**
 * Desktop → cloud "up" sync: pending sales (including sales made in the
 * still-open shift) are uploaded the moment they complete, and the per-sale
 * {@code cloud_synced_at} marker is only stamped after the cloud acknowledges.
 */
class DesktopSyncPushServiceTest {

    private static final String LOCAL_BUSINESS = "local-biz";
    private static final String CLOUD_ORIGIN = "https://shop.example.com";

    private final ShiftRepository shiftRepository = mock(ShiftRepository.class);
    private final SaleRepository saleRepository = mock(SaleRepository.class);
    private final SaleItemRepository saleItemRepository = mock(SaleItemRepository.class);
    private final SalePaymentRepository salePaymentRepository = mock(SalePaymentRepository.class);
    private final CustomerRepository customerRepository = mock(CustomerRepository.class);
    private final CustomerPhoneRepository customerPhoneRepository = mock(CustomerPhoneRepository.class);
    private final CreditAccountRepository creditAccountRepository = mock(CreditAccountRepository.class);
    private final SupplierRepository supplierRepository = mock(SupplierRepository.class);
    private final SupplierContactRepository supplierContactRepository = mock(SupplierContactRepository.class);
    private final CloudSyncSession cloudSyncSession = mock(CloudSyncSession.class);

    private final RestClient.Builder restClientBuilder = RestClient.builder();
    private MockRestServiceServer server;

    private DesktopSyncPushService service;

    @BeforeEach
    void setUp() {
        when(cloudSyncSession.load()).thenReturn(Optional.of(new CloudSyncSession.Session(
            CLOUD_ORIGIN, "cloud-biz", "access-token", "refresh-token",
            "owner-id", List.of("staff-1"), Instant.EPOCH, null)));
        when(saleItemRepository.findBySaleIdOrderByLineIndexAsc(anyString())).thenReturn(List.of());
        when(salePaymentRepository.findBySaleIdOrderBySortOrderAsc(anyString())).thenReturn(List.of());

        service = new DesktopSyncPushService(
            shiftRepository, saleRepository, saleItemRepository,
            salePaymentRepository, customerRepository, customerPhoneRepository,
            creditAccountRepository, supplierRepository, supplierContactRepository,
            cloudSyncSession, restClientBuilder);
        ReflectionTestUtils.setField(service, "desktopBusinessId", LOCAL_BUSINESS);

        server = MockRestServiceServer.bindTo(restClientBuilder).build();
    }

    private static Shift shift(String id, String status) {
        Shift s = new Shift();
        s.setId(id);
        s.setBusinessId(LOCAL_BUSINESS);
        s.setBranchId("branch-1");
        s.setTillDeviceKey("till-1");
        s.setStatus(status);
        s.setOpeningCash(new BigDecimal("5000.00"));
        s.setExpectedClosingCash(new BigDecimal("5000.00"));
        s.setOpenedAt(Instant.parse("2026-08-20T08:00:00Z"));
        if (SalesConstants.SHIFT_STATUS_CLOSED.equals(status)) {
            s.setClosedAt(Instant.parse("2026-08-20T18:00:00Z"));
        }
        return s;
    }

    private static Sale sale(String id, String shiftId, String status) {
        Sale s = new Sale();
        s.setId(id);
        s.setBusinessId(LOCAL_BUSINESS);
        s.setBranchId("branch-1");
        s.setShiftId(shiftId);
        s.setStatus(status);
        s.setIdempotencyKey("idem-" + id);
        s.setGrandTotal(new BigDecimal("1500.00"));
        s.setSoldBy("staff-1");
        s.setSoldAt(Instant.parse("2026-08-20T10:00:00Z"));
        return s;
    }

    @Test
    void pushesCompletedSaleFromOpenShiftImmediately() {
        Shift openShift = shift("shift-1", SalesConstants.SHIFT_STATUS_OPEN);
        Sale completed = sale("sale-1", "shift-1", SalesConstants.SALE_STATUS_COMPLETED);

        when(saleRepository.findByBusinessIdAndCloudSyncedAtIsNullOrderBySoldAtAsc(LOCAL_BUSINESS))
            .thenReturn(List.of(completed));
        when(shiftRepository.findByBusinessIdAndStatusAndCloudSyncedAtIsNullOrderByClosedAtAsc(
                LOCAL_BUSINESS, SalesConstants.SHIFT_STATUS_CLOSED))
            .thenReturn(List.of());
        when(shiftRepository.findAllById(Set.of("shift-1"))).thenReturn(List.of(openShift));

        // The regression: sales have status "completed" — the old code queried
        // sales by shift status "closed" and silently uploaded zero sales.
        server.expect(requestTo(CLOUD_ORIGIN + "/api/v1/desktop/sync/shifts"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer access-token"))
            .andExpect(header("X-Tenant-Id", "cloud-biz"))
            .andRespond(withSuccess(
                "{\"shiftsIngested\":1,\"salesIngested\":1,\"salesSkipped\":0,\"suppliersIngested\":0}",
                MediaType.APPLICATION_JSON));

        SyncPushResult result = service.pushPending();

        server.verify();
        assertEquals(1, result.salesPushed());
        assertEquals(0, result.shiftsPushed(), "open shift is not stamped synced");
        verify(saleRepository).saveAll(org.mockito.ArgumentMatchers.argThat(list -> {
            Sale stamped = ((java.util.List<Sale>) list).get(0);
            return stamped.getCloudSyncedAt() != null;
        }));
    }

    @Test
    void closedShiftIsStampedOnlyAfterAllItsSalesAreSynced() {
        Shift closedShift = shift("shift-2", SalesConstants.SHIFT_STATUS_CLOSED);
        Sale completed = sale("sale-2", "shift-2", SalesConstants.SALE_STATUS_COMPLETED);

        when(saleRepository.findByBusinessIdAndCloudSyncedAtIsNullOrderBySoldAtAsc(LOCAL_BUSINESS))
            .thenReturn(List.of(completed));
        when(shiftRepository.findByBusinessIdAndStatusAndCloudSyncedAtIsNullOrderByClosedAtAsc(
                LOCAL_BUSINESS, SalesConstants.SHIFT_STATUS_CLOSED))
            .thenReturn(List.of(closedShift));
        when(shiftRepository.findAllById(Set.of("shift-2"))).thenReturn(List.of(closedShift));
        when(saleRepository.countByShiftIdAndCloudSyncedAtIsNull("shift-2")).thenReturn(0L);

        server.expect(requestTo(CLOUD_ORIGIN + "/api/v1/desktop/sync/shifts"))
            .andRespond(withSuccess(
                "{\"shiftsIngested\":1,\"salesIngested\":1,\"salesSkipped\":0,\"suppliersIngested\":0}",
                MediaType.APPLICATION_JSON));

        SyncPushResult result = service.pushPending();

        server.verify();
        assertEquals(1, result.shiftsPushed());
        verify(shiftRepository).saveAll(org.mockito.ArgumentMatchers.argThat(list ->
            ((java.util.List<Shift>) list).get(0).getCloudSyncedAt() != null));
    }

    @Test
    void legacyShiftWithoutTillDeviceKeyPushesNullKey() throws Exception {
        // Shifts opened before X-Till-Device-Id existed (or via a headerless
        // client) carry a null till_device_key. The cloud treats that as the
        // shared branch drawer — the batch must upload null, never blank, or
        // the ingest 400s with "tillDeviceKey must not be blank".
        Shift legacy = shift("shift-legacy", SalesConstants.SHIFT_STATUS_CLOSED);
        legacy.setTillDeviceKey(null);
        Sale completed = sale("sale-legacy", "shift-legacy", SalesConstants.SALE_STATUS_COMPLETED);

        when(saleRepository.findByBusinessIdAndCloudSyncedAtIsNullOrderBySoldAtAsc(LOCAL_BUSINESS))
            .thenReturn(List.of(completed));
        when(shiftRepository.findByBusinessIdAndStatusAndCloudSyncedAtIsNullOrderByClosedAtAsc(
                LOCAL_BUSINESS, SalesConstants.SHIFT_STATUS_CLOSED))
            .thenReturn(List.of(legacy));
        when(shiftRepository.findAllById(Set.of("shift-legacy"))).thenReturn(List.of(legacy));
        when(saleRepository.countByShiftIdAndCloudSyncedAtIsNull("shift-legacy")).thenReturn(0L);

        server.expect(requestTo(CLOUD_ORIGIN + "/api/v1/desktop/sync/shifts"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(request -> {
                String body = ((MockClientHttpRequest) request).getBodyAsString();
                JsonNode tillKey = new ObjectMapper()
                    .readTree(body).path("shifts").path(0).path("tillDeviceKey");
                assertTrue(tillKey.isNull(),
                    "legacy shift must upload null tillDeviceKey, got: " + tillKey);
            })
            .andRespond(withSuccess(
                "{\"shiftsIngested\":1,\"salesIngested\":1,\"salesSkipped\":0,\"suppliersIngested\":0}",
                MediaType.APPLICATION_JSON));

        SyncPushResult result = service.pushPending();

        server.verify();
        assertEquals(1, result.shiftsPushed());
    }

    @Test
    void noCloudMappingSkipsPush() {
        when(cloudSyncSession.load()).thenReturn(Optional.empty());

        SyncPushResult result = service.pushPending();

        assertFalse(result.configured());
        assertEquals(0, result.salesPushed());
        verify(saleRepository, never()).findByBusinessIdAndCloudSyncedAtIsNullOrderBySoldAtAsc(anyString());
    }

    @Test
    void pushesDirtyCustomersWithTheirCreditState() {
        // A customer created on the till (or whose balance changed locally) is
        // uploaded even with no pending sales, and stamped synced after ack.
        Customer jane = new Customer();
        jane.setId("c-1");
        jane.setBusinessId(LOCAL_BUSINESS);
        jane.setName("Jane Doe");
        when(customerRepository.findDirtyForDesktopSync(LOCAL_BUSINESS))
            .thenReturn(List.of(jane));
        when(customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc("c-1"))
            .thenReturn(List.of());
        CreditAccount acc = new CreditAccount();
        acc.setBalanceOwed(new BigDecimal("500.00"));
        when(creditAccountRepository.findByCustomerIdAndBusinessId("c-1", LOCAL_BUSINESS))
            .thenReturn(Optional.of(acc));
        when(saleRepository.findByBusinessIdAndCloudSyncedAtIsNullOrderBySoldAtAsc(LOCAL_BUSINESS))
            .thenReturn(List.of());
        when(shiftRepository.findByBusinessIdAndStatusAndCloudSyncedAtIsNullOrderByClosedAtAsc(
                LOCAL_BUSINESS, SalesConstants.SHIFT_STATUS_CLOSED))
            .thenReturn(List.of());

        server.expect(requestTo(CLOUD_ORIGIN + "/api/v1/desktop/sync/shifts"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(
                "{\"shiftsIngested\":0,\"salesIngested\":0,\"salesSkipped\":0,\"suppliersIngested\":0}",
                MediaType.APPLICATION_JSON));

        SyncPushResult result = service.pushPending();

        server.verify();
        assertTrue(result.configured());
        verify(customerRepository).saveAll(org.mockito.ArgumentMatchers.argThat(list -> {
            Customer stamped = ((java.util.List<Customer>) list).get(0);
            return stamped.getCloudSyncedAt() != null;
        }));
    }

    @Test
    void nothingPendingIsAnEmptySuccess() {
        when(saleRepository.findByBusinessIdAndCloudSyncedAtIsNullOrderBySoldAtAsc(LOCAL_BUSINESS))
            .thenReturn(List.of());
        when(shiftRepository.findByBusinessIdAndStatusAndCloudSyncedAtIsNullOrderByClosedAtAsc(
                LOCAL_BUSINESS, SalesConstants.SHIFT_STATUS_CLOSED))
            .thenReturn(List.of());

        SyncPushResult result = service.pushPending();

        assertTrue(result.configured());
        assertEquals(0, result.salesPushed());
        assertNotNull(result);
    }
}
