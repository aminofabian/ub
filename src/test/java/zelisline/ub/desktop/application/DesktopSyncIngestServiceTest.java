package zelisline.ub.desktop.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import zelisline.ub.desktop.api.dto.ShiftSyncAck;
import zelisline.ub.desktop.api.dto.ShiftSyncRequest;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.platform.realtime.RealtimeBridge;
import zelisline.ub.purchasing.repository.RawPurchaseLineRepository;
import zelisline.ub.purchasing.repository.RawPurchaseSessionRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceLineRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceRepository;
import zelisline.ub.purchasing.repository.StockMovementRepository;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.sales.repository.SalePaymentRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.suppliers.repository.SupplierContactRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;

/**
 * Cloud-side ingest of till-uploaded shifts: new sales are recorded and
 * announced in realtime to connected POS/dashboard sessions; already-seen
 * sales (same id or idempotency key) are skipped, not re-announced.
 */
class DesktopSyncIngestServiceTest {

    private final ShiftRepository shiftRepository = mock(ShiftRepository.class);
    private final SaleRepository saleRepository = mock(SaleRepository.class);
    private final SaleItemRepository saleItemRepository = mock(SaleItemRepository.class);
    private final SalePaymentRepository salePaymentRepository = mock(SalePaymentRepository.class);
    private final CustomerRepository customerRepository = mock(CustomerRepository.class);
    private final CustomerPhoneRepository customerPhoneRepository = mock(CustomerPhoneRepository.class);
    private final CreditAccountRepository creditAccountRepository = mock(CreditAccountRepository.class);
    private final SupplierRepository supplierRepository = mock(SupplierRepository.class);
    private final SupplierContactRepository supplierContactRepository = mock(SupplierContactRepository.class);
    private final RawPurchaseSessionRepository rawPurchaseSessionRepository = mock(RawPurchaseSessionRepository.class);
    private final RawPurchaseLineRepository rawPurchaseLineRepository = mock(RawPurchaseLineRepository.class);
    private final SupplierInvoiceRepository supplierInvoiceRepository = mock(SupplierInvoiceRepository.class);
    private final SupplierInvoiceLineRepository supplierInvoiceLineRepository = mock(SupplierInvoiceLineRepository.class);
    private final ItemRepository itemRepository = mock(ItemRepository.class);
    private final StockMovementRepository stockMovementRepository = mock(StockMovementRepository.class);
    private final zelisline.ub.storefront.application.WebOrderFulfillmentService webOrderFulfillmentService =
        mock(zelisline.ub.storefront.application.WebOrderFulfillmentService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private DesktopSyncIngestService service() {
        return new DesktopSyncIngestService(
            shiftRepository, saleRepository, saleItemRepository,
            salePaymentRepository, customerRepository, customerPhoneRepository,
            creditAccountRepository, supplierRepository, supplierContactRepository,
            rawPurchaseSessionRepository, rawPurchaseLineRepository,
            supplierInvoiceRepository, supplierInvoiceLineRepository,
            itemRepository, stockMovementRepository, webOrderFulfillmentService,
            eventPublisher);
    }

    private static ShiftSyncRequest requestWithOneSale(String saleId, String idempotencyKey) {
        return new ShiftSyncRequest(List.of(new ShiftSyncRequest.ShiftData(
            "shift-1",
            "branch-1",
            "till-1",
            SalesConstants.SHIFT_STATUS_OPEN,
            "owner-id",
            new BigDecimal("5000.00"),
            new BigDecimal("5000.00"),
            null,
            null,
            null,
            null,
            null,
            false,
            Instant.parse("2026-08-20T08:00:00Z"),
            null,
            List.of(new ShiftSyncRequest.SaleData(
                saleId,
                "branch-1",
                SalesConstants.SALE_STATUS_COMPLETED,
                idempotencyKey,
                new BigDecimal("1500.00"),
                null,
                "owner-id",
                "customer-1",
                Instant.parse("2026-08-20T10:00:00Z"),
                null,
                null,
                null,
                List.of(),
                List.of()
            ))
        )), null, null);
    }

    @Test
    void ingestsNewSaleAndPublishesSaleCompleted() {
        when(saleRepository.findByBusinessIdAndIdempotencyKey("cloud-biz", "idem-1"))
            .thenReturn(Optional.empty());
        when(saleRepository.findByIdAndBusinessId("sale-1", "cloud-biz"))
            .thenReturn(Optional.empty());
        when(shiftRepository.findByIdAndBusinessId("shift-1", "cloud-biz"))
            .thenReturn(Optional.empty());

        ShiftSyncAck ack = service().ingest("cloud-biz", requestWithOneSale("sale-1", "idem-1"));

        assertEquals(1, ack.salesIngested());
        assertEquals(0, ack.salesSkipped());
        // shifts.opened_by is NOT NULL on the cloud — the ingest must carry the
        // till's (remapped) opener through, or the whole batch rolls back.
        verify(shiftRepository).save(org.mockito.ArgumentMatchers.argThat(shift ->
            "owner-id".equals(((zelisline.ub.sales.domain.Shift) shift).getOpenedBy())));
        // The sale keeps the till's customer reference (customers are upserted
        // earlier in the same batch, so the sales.customer_id FK resolves).
        verify(saleRepository).save(org.mockito.ArgumentMatchers.argThat(sale ->
            "customer-1".equals(((zelisline.ub.sales.domain.Sale) sale).getCustomerId())));
        // The cloud announces the till sale to live POS/dashboard sessions.
        verify(eventPublisher).publishEvent(eq(new RealtimeBridge.SaleCompletedEvent(
            "cloud-biz", "branch-1", "sale-1", new BigDecimal("1500.00"))));
    }

    @Test
    void upsertsTillCustomersBeforeSales() {
        ShiftSyncRequest request = new ShiftSyncRequest(
            List.of(new ShiftSyncRequest.ShiftData(
                "shift-1", "branch-1", "till-1", SalesConstants.SHIFT_STATUS_OPEN,
                "owner-id", new BigDecimal("5000.00"), new BigDecimal("5000.00"),
                null, null, null, null, null, false,
                Instant.parse("2026-08-20T08:00:00Z"), null, List.of())),
            List.of(new ShiftSyncRequest.CustomerData(
                "customer-1",
                "Jane Doe",
                "jane@example.com",
                null,
                List.of(new ShiftSyncRequest.CustomerPhoneData(
                    "phone-1", "254700111222", true)),
                new ShiftSyncRequest.CreditAccountData(
                    new BigDecimal("500.00"), BigDecimal.ZERO, 12, new BigDecimal("5000.00")))),
            null);
        when(customerRepository.findByIdAndBusinessIdAndDeletedAtIsNull("customer-1", "cloud-biz"))
            .thenReturn(Optional.empty());
        when(customerRepository.nextCustomerNo("cloud-biz")).thenReturn(Optional.of(4L));
        when(customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc(any()))
            .thenReturn(List.of());
        when(customerPhoneRepository.existsByBusinessIdAndPhone("cloud-biz", "254700111222"))
            .thenReturn(false);
        when(creditAccountRepository.findByCustomerIdAndBusinessId("customer-1", "cloud-biz"))
            .thenReturn(Optional.empty());

        service().ingest("cloud-biz", request);

        // New cloud customer gets the next sequential number so the portal's
        // customer numbering stays intact.
        verify(customerRepository).saveAndFlush(org.mockito.ArgumentMatchers.argThat(c ->
            "customer-1".equals(((zelisline.ub.credits.domain.Customer) c).getId())
                && Long.valueOf(5L).equals(((zelisline.ub.credits.domain.Customer) c).getCustomerNo())));
        verify(customerPhoneRepository).save(org.mockito.ArgumentMatchers.argThat(p ->
            "254700111222".equals(((zelisline.ub.credits.domain.CustomerPhone) p).getPhone())));
        // The till's authoritative balance is adopted on the cloud copy.
        verify(creditAccountRepository).save(org.mockito.ArgumentMatchers.argThat(a ->
            new BigDecimal("500.00").compareTo(
                ((zelisline.ub.credits.domain.CreditAccount) a).getBalanceOwed()) == 0));
    }

    @Test
    void skipsAlreadySeenSaleWithoutAnnouncing() {
        when(saleRepository.findByBusinessIdAndIdempotencyKey("cloud-biz", "idem-1"))
            .thenReturn(Optional.empty());
        when(saleRepository.findByIdAndBusinessId("sale-1", "cloud-biz"))
            .thenReturn(Optional.of(org.mockito.Mockito.mock(zelisline.ub.sales.domain.Sale.class)));

        ShiftSyncAck ack = service().ingest("cloud-biz", requestWithOneSale("sale-1", "idem-1"));

        assertEquals(0, ack.salesIngested());
        assertEquals(1, ack.salesSkipped());
        verify(eventPublisher, never()).publishEvent(any(RealtimeBridge.SaleCompletedEvent.class));
    }

    @Test
    void blankTillDeviceKeyIsNormalizedToNull() {
        // A legacy shift carries no till device key — a blank value must be
        // stored as null (shared branch drawer), never as an empty string.
        ShiftSyncRequest request = new ShiftSyncRequest(
            List.of(new ShiftSyncRequest.ShiftData(
                "shift-1", "branch-1", "   ", SalesConstants.SHIFT_STATUS_OPEN,
                "owner-id", new BigDecimal("5000.00"), new BigDecimal("5000.00"),
                null, null, null, null, null, false,
                Instant.parse("2026-08-20T08:00:00Z"), null, List.of())),
            null,
            null);
        when(shiftRepository.findByIdAndBusinessId("shift-1", "cloud-biz"))
            .thenReturn(Optional.empty());

        service().ingest("cloud-biz", request);

        verify(shiftRepository).save(org.mockito.ArgumentMatchers.argThat(shift ->
            ((zelisline.ub.sales.domain.Shift) shift).getTillDeviceKey() == null));
    }

    @Test
    void upsertsTillSuppliersIdPreservingly() {
        ShiftSyncRequest request = new ShiftSyncRequest(
            List.of(),
            null,
            List.of(new ShiftSyncRequest.SupplierData(
                "supplier-1",
                "Kilimanjaro Distributors",
                "SUP-001",
                "distributor",
                null,
                false,
                30,
                new BigDecimal("100000.00"),
                "active",
                null,
                null,
                null,
                "mobile_wallet",
                "254700999888",
                null,
                null,
                null,
                List.of(new ShiftSyncRequest.SupplierContactData(
                    "contact-1", "Amina", "Sales rep", "254701222333", null, true)))));
        when(supplierRepository.findByIdAndBusinessId("supplier-1", "cloud-biz"))
            .thenReturn(Optional.empty());
        when(supplierContactRepository.findBySupplierIdOrderByPrimaryContactDescNameAsc(any()))
            .thenReturn(List.of());

        ShiftSyncAck ack = service().ingest("cloud-biz", request);

        assertEquals(1, ack.suppliersIngested());
        // The cloud adopts the till's supplier id so both sides reference the
        // same directory row.
        verify(supplierRepository).saveAndFlush(org.mockito.ArgumentMatchers.argThat(s ->
            "supplier-1".equals(((zelisline.ub.suppliers.domain.Supplier) s).getId())
                && "mobile_wallet".equals(
                    ((zelisline.ub.suppliers.domain.Supplier) s).getPayoutType())));
        verify(supplierContactRepository).save(org.mockito.ArgumentMatchers.argThat(c ->
            "contact-1".equals(((zelisline.ub.suppliers.domain.SupplierContact) c).getId())
                && c.isPrimaryContact()));
    }
}
