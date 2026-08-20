package zelisline.ub.desktop.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import zelisline.ub.desktop.api.dto.ShiftSyncAck;
import zelisline.ub.desktop.api.dto.ShiftSyncRequest;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.sales.domain.SalePayment;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.sales.repository.SalePaymentRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.sales.repository.ShiftRepository;

/**
 * Desktop-side push of closed shifts to the shop's online instance — the
 * "up" direction of store-and-forward sync (see {@code ShiftSyncRequest}).
 *
 * <p>Reads the cloud mapping written by {@link DesktopConnectService}
 * ({@code APP_DATA/conf/cloud-sync.json}), uploads every closed shift whose
 * {@code cloud_synced_at} marker is still null, and stamps the marker only
 * after the cloud acknowledges the batch. A failed push leaves the shifts
 * pending so the next run retries them — and the cloud's idempotent ingest
 * (per-sale {@code idempotencyKey}) makes retries safe.
 *
 * <p>v1 notes: the stored cloud access token is reused until it expires — if
 * the cloud rejects it with 401 the caller should ask the owner to reconnect.
 * Local cashier ids are remapped to the cloud owner user id (staff sync is a
 * follow-up); customer and ledger references are dropped.
 */
@Service
@Profile("desktop")
@RequiredArgsConstructor
public class DesktopSyncPushService {

    private static final Logger log = LoggerFactory.getLogger(DesktopSyncPushService.class);

    private final ShiftRepository shiftRepository;
    private final SaleRepository saleRepository;
    private final SaleItemRepository saleItemRepository;
    private final SalePaymentRepository salePaymentRepository;
    private final CloudSyncSession cloudSyncSession;

    @Value("${app.desktop.business-id:}")
    private String desktopBusinessId;

    public record SyncPushResult(int shiftsPushed, int salesPushed, boolean configured) {}

    public SyncPushResult pushPending() {
        String localId = desktopBusinessId == null ? "" : desktopBusinessId.trim();
        CloudSyncSession.Session mapping = cloudSyncSession
            .load()
            .orElse(null);
        if (mapping == null || localId.isEmpty()) {
            log.info("[DesktopSync] no cloud mapping — nothing to push.");
            return new SyncPushResult(0, 0, false);
        }

        List<Shift> pending = shiftRepository
            .findByBusinessIdAndStatusAndCloudSyncedAtIsNullOrderByClosedAtAsc(
                localId,
                SalesConstants.SHIFT_STATUS_CLOSED
            );
        if (pending.isEmpty()) {
            return new SyncPushResult(0, 0, true);
        }
        log.info("[DesktopSync] pushing {} closed shift(s) to {}", pending.size(), mapping.origin());

        ShiftSyncRequest batch = buildBatch(localId, pending, mapping.ownerUserId());

        RestClient client = RestClient.builder().baseUrl(mapping.origin()).build();
        ShiftSyncAck ack = postBatch(client, mapping, batch);

        Instant syncedAt = Instant.now();
        pending.forEach(s -> s.setCloudSyncedAt(syncedAt));
        shiftRepository.saveAll(pending);
        log.info(
            "[DesktopSync] acknowledged: {} sale(s) new, {} skipped; marked {} shift(s) synced",
            ack.salesIngested(),
            ack.salesSkipped(),
            pending.size()
        );
        return new SyncPushResult(pending.size(), ack.salesIngested(), true);
    }

    private ShiftSyncRequest buildBatch(
            String localId,
            List<Shift> shifts,
            String ownerUserId) {
        List<ShiftSyncRequest.ShiftData> data = new ArrayList<>();
        for (Shift shift : shifts) {
            List<ShiftSyncRequest.SaleData> sales = saleRepository
                .findByShiftIdAndStatus(shift.getId(), SalesConstants.SHIFT_STATUS_CLOSED)
                .stream()
                .map(s -> toSaleData(s, ownerUserId))
                .toList();

            data.add(new ShiftSyncRequest.ShiftData(
                shift.getId(),
                shift.getBranchId(),
                shift.getTillDeviceKey(),
                shift.getStatus(),
                shift.getOpeningCash(),
                shift.getExpectedClosingCash(),
                shift.getCountedClosingCash(),
                shift.getClosingVariance(),
                shift.getOpeningNotes(),
                shift.getClosingNotes(),
                shift.getVarianceReason(),
                shift.isBlindClosing(),
                shift.getOpenedAt(),
                shift.getClosedAt(),
                sales
            ));
        }
        return new ShiftSyncRequest(data);
    }

    private ShiftSyncRequest.SaleData toSaleData(Sale sale, String ownerUserId) {
        List<ShiftSyncRequest.SaleItemData> items = saleItemRepository
            .findBySaleIdOrderByLineIndexAsc(sale.getId())
            .stream()
            .map(this::toItemData)
            .toList();
        List<ShiftSyncRequest.SalePaymentData> payments = salePaymentRepository
            .findBySaleIdOrderBySortOrderAsc(sale.getId())
            .stream()
            .map(this::toPaymentData)
            .toList();

        return new ShiftSyncRequest.SaleData(
            sale.getId(),
            sale.getBranchId(),
            sale.getStatus(),
            sale.getIdempotencyKey(),
            sale.getGrandTotal(),
            sale.getCashReceived(),
            ownerUserId,
            sale.getSoldAt(),
            sale.getVoidedAt(),
            sale.getVoidNotes(),
            sale.getRefundedTotal(),
            items,
            payments
        );
    }

    private ShiftSyncRequest.SaleItemData toItemData(SaleItem item) {
        return new ShiftSyncRequest.SaleItemData(
            item.getId(),
            item.getLineIndex(),
            item.getLineKind(),
            item.getLineLabel(),
            item.getItemId(),
            item.getBatchId(),
            item.getQuantity(),
            item.getUnitPrice(),
            item.getLineTotal(),
            item.getUnitCost(),
            item.getCostTotal(),
            item.getProfit(),
            item.getRegularUnitPrice(),
            item.getDiscountAmount(),
            item.getDiscountId(),
            item.getDiscountName()
        );
    }

    private ShiftSyncRequest.SalePaymentData toPaymentData(SalePayment payment) {
        return new ShiftSyncRequest.SalePaymentData(
            payment.getId(),
            payment.getMethod(),
            payment.getAmount(),
            payment.getReference(),
            payment.getSortOrder()
        );
    }

    /**
     * POST the batch to the cloud; refreshes the stored token once and retries
     * when the access token has expired.
     */
    private ShiftSyncAck postBatch(
            RestClient client,
            CloudSyncSession.Session session,
            ShiftSyncRequest batch) {
        try {
            return doPost(client, session, batch);
        } catch (Exception e) {
            if (!isUnauthorized(e)) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not upload sales to the online shop (" + e.getMessage() + ")"
                );
            }
            CloudSyncSession.Session refreshed = cloudSyncSession
                .refresh(client, session)
                .orElse(null);
            if (refreshed == null) {
                throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Your online-shop session has expired — open Settings → Sync to reconnect"
                );
            }
            try {
                return doPost(client, refreshed, batch);
            } catch (Exception e2) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not upload sales to the online shop (" + e2.getMessage() + ")"
                );
            }
        }
    }

    private ShiftSyncAck doPost(
            RestClient client,
            CloudSyncSession.Session session,
            ShiftSyncRequest batch) {
        ShiftSyncAck ack = client
            .post()
            .uri("/api/v1/desktop/sync/shifts")
            .header("Authorization", "Bearer " + session.accessToken())
            .header("X-Tenant-Id", session.cloudBusinessId())
            .contentType(MediaType.APPLICATION_JSON)
            .body(batch)
            .retrieve()
            .body(ShiftSyncAck.class);
        if (ack == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "The online shop returned an empty acknowledgment"
            );
        }
        return ack;
    }

    private static boolean isUnauthorized(Exception e) {
        return e.getMessage() != null
            && (e.getMessage().contains("401") || e.getMessage().contains("Unauthorized"));
    }
}
