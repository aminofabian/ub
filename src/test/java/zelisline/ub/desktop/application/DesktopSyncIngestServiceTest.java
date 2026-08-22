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
import zelisline.ub.platform.realtime.RealtimeBridge;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.sales.repository.SalePaymentRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.sales.repository.ShiftRepository;

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
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private DesktopSyncIngestService service() {
        return new DesktopSyncIngestService(
            shiftRepository, saleRepository, saleItemRepository,
            salePaymentRepository, eventPublisher);
    }

    private static ShiftSyncRequest requestWithOneSale(String saleId, String idempotencyKey) {
        return new ShiftSyncRequest(List.of(new ShiftSyncRequest.ShiftData(
            "shift-1",
            "branch-1",
            "till-1",
            SalesConstants.SHIFT_STATUS_OPEN,
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
                Instant.parse("2026-08-20T10:00:00Z"),
                null,
                null,
                null,
                List.of(),
                List.of()
            ))
        )));
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
        // The cloud announces the till sale to live POS/dashboard sessions.
        verify(eventPublisher).publishEvent(eq(new RealtimeBridge.SaleCompletedEvent(
            "cloud-biz", "branch-1", "sale-1", new BigDecimal("1500.00"))));
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
}
