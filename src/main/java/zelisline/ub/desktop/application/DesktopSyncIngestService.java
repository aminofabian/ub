package zelisline.ub.desktop.application;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zelisline.ub.desktop.api.dto.ShiftSyncAck;
import zelisline.ub.desktop.api.dto.ShiftSyncRequest;
import zelisline.ub.platform.realtime.RealtimeBridge;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.sales.domain.SalePayment;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.sales.repository.SalePaymentRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.sales.repository.ShiftRepository;

/**
 * Cloud-side ingest for till-uploaded shifts (the "up" direction of
 * store-and-forward sync — see {@code ShiftSyncRequest}).
 *
 * <p>Idempotent: shifts are upserted by id, and each sale is inserted only if
 * neither its id nor its {@code idempotencyKey} already exists for the
 * business. Because the whole batch runs in one transaction, a failed push
 * rolls back entirely and the till simply retries later.
 *
 * <p>v1 scope: sales are recorded directly (visible in cloud reports, and
 * announced in realtime to connected POS/dashboard sessions) but the
 * heavy pipelines are intentionally not re-run — no receipt-number allocation,
 * no ledger journal postings, no stock deduction, no customer resolution.
 * Those are follow-ups, and the till's local copy is the source of truth for
 * its own operation.
 */
@Service
@RequiredArgsConstructor
public class DesktopSyncIngestService {

    private static final Logger log = LoggerFactory.getLogger(DesktopSyncIngestService.class);

    private final ShiftRepository shiftRepository;
    private final SaleRepository saleRepository;
    private final SaleItemRepository saleItemRepository;
    private final SalePaymentRepository salePaymentRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public ShiftSyncAck ingest(String businessId, ShiftSyncRequest request) {
        int shifts = 0;
        int salesIngested = 0;
        int salesSkipped = 0;

        for (ShiftSyncRequest.ShiftData data : request.shifts()) {
            ingestShift(businessId, data);
            shifts++;

            if (data.sales() == null) {
                continue;
            }
            for (ShiftSyncRequest.SaleData saleData : data.sales()) {
                if (saleRepository
                        .findByBusinessIdAndIdempotencyKey(businessId, saleData.idempotencyKey())
                        .isPresent()
                        || saleRepository
                            .findByIdAndBusinessId(saleData.id(), businessId)
                            .isPresent()) {
                    salesSkipped++;
                    continue;
                }
                ingestSale(businessId, data.id(), saleData);
                salesIngested++;
                // Realtime fan-out: tell connected cloud POS sessions / dashboards
                // that a till sale just landed (same event a web POS sale fires).
                eventPublisher.publishEvent(new RealtimeBridge.SaleCompletedEvent(
                    businessId,
                    saleData.branchId(),
                    saleData.id(),
                    saleData.grandTotal()));
            }
        }

        log.info(
            "[DesktopSync] ingested {} shift(s): {} new sale(s), {} already seen",
            shifts,
            salesIngested,
            salesSkipped
        );
        return new ShiftSyncAck(shifts, salesIngested, salesSkipped);
    }

    private void ingestShift(String businessId, ShiftSyncRequest.ShiftData data) {
        Shift shift = shiftRepository
            .findByIdAndBusinessId(data.id(), businessId)
            .orElseGet(() -> {
                Shift created = new Shift();
                created.setId(data.id());
                created.setBusinessId(businessId);
                return created;
            });

        shift.setBranchId(data.branchId());
        shift.setTillDeviceKey(data.tillDeviceKey());
        shift.setStatus(data.status());
        shift.setOpeningCash(data.openingCash());
        shift.setExpectedClosingCash(data.expectedClosingCash());
        shift.setCountedClosingCash(data.countedClosingCash());
        shift.setClosingVariance(data.closingVariance());
        shift.setOpeningNotes(data.openingNotes());
        shift.setClosingNotes(data.closingNotes());
        shift.setVarianceReason(data.varianceReason());
        shift.setBlindClosing(data.blindClosing());
        shift.setOpenedAt(data.openedAt());
        shift.setClosedAt(data.closedAt());
        shiftRepository.save(shift);
    }

    private void ingestSale(String businessId, String shiftId, ShiftSyncRequest.SaleData data) {
        Sale sale = new Sale();
        sale.setId(data.id());
        sale.setBusinessId(businessId);
        sale.setBranchId(data.branchId());
        sale.setShiftId(shiftId);
        sale.setStatus(data.status());
        sale.setIdempotencyKey(data.idempotencyKey());
        sale.setGrandTotal(data.grandTotal());
        sale.setCashReceived(data.cashReceived());
        sale.setSoldBy(data.soldBy());
        // Customers are not synced in v1 — the till keeps its own references.
        sale.setCustomerId(null);
        sale.setSoldAt(data.soldAt() == null ? Instant.now() : data.soldAt());
        sale.setVoidedAt(data.voidedAt());
        sale.setVoidNotes(data.voidNotes());
        sale.setRefundedTotal(data.refundedTotal() == null
            ? java.math.BigDecimal.ZERO
            : data.refundedTotal());
        saleRepository.save(sale);

        if (data.items() != null) {
            for (ShiftSyncRequest.SaleItemData itemData : data.items()) {
                SaleItem item = new SaleItem();
                item.setId(itemData.id());
                item.setSaleId(sale.getId());
                item.setLineIndex(itemData.lineIndex());
                item.setLineKind(itemData.lineKind() == null
                    ? zelisline.ub.sales.domain.SaleLineKinds.ITEM
                    : itemData.lineKind());
                item.setLineLabel(itemData.lineLabel());
                item.setItemId(itemData.itemId());
                item.setBatchId(itemData.batchId());
                item.setQuantity(itemData.quantity());
                item.setUnitPrice(itemData.unitPrice());
                item.setLineTotal(itemData.lineTotal());
                item.setUnitCost(itemData.unitCost());
                item.setCostTotal(itemData.costTotal());
                item.setProfit(itemData.profit());
                item.setRegularUnitPrice(itemData.regularUnitPrice());
                item.setDiscountAmount(itemData.discountAmount());
                item.setDiscountId(itemData.discountId());
                item.setDiscountName(itemData.discountName());
                saleItemRepository.save(item);
            }
        }

        if (data.payments() != null) {
            for (ShiftSyncRequest.SalePaymentData paymentData : data.payments()) {
                SalePayment payment = new SalePayment();
                payment.setId(paymentData.id());
                payment.setSaleId(sale.getId());
                payment.setMethod(paymentData.method());
                payment.setAmount(paymentData.amount());
                payment.setReference(paymentData.reference());
                payment.setSortOrder(paymentData.sortOrder());
                salePaymentRepository.save(payment);
            }
        }
    }
}
