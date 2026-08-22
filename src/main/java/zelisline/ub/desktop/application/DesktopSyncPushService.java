package zelisline.ub.desktop.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
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
 * Desktop-side push of till sales to the shop's online instance — the "up"
 * direction of store-and-forward sync (see {@code ShiftSyncRequest}).
 *
 * <p>Sync is tracked <em>per sale</em>: every sale whose {@code cloud_synced_at}
 * marker is still null is uploaded, whether its shift is open or closed. That
 * is what makes realtime sync work — a sale completed at the till is pushed the
 * moment it happens (see {@link DesktopSaleCompletedSyncListener}), and a till
 * that comes online later flushes everything that piled up while offline
 * (startup + periodic retry, see {@link DesktopSyncScheduler}).
 *
 * <p>Closed shifts that were never fully uploaded are pushed once more at close
 * so the cloud gets their final state (closing cash / variance). The cloud
 * ingest is idempotent (per-sale {@code idempotencyKey}), so a failed push
 * leaves the markers untouched and the next run retries safely.
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
    private final RestClient.Builder restClientBuilder;

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

        // Sales not yet acknowledged by the cloud — includes sales made in the
        // still-open shift (realtime) and anything that piled up while offline.
        List<Sale> pendingSales = saleRepository
            .findByBusinessIdAndCloudSyncedAtIsNullOrderBySoldAtAsc(localId);
        // Closed shifts whose final state was never uploaded (closing cash /
        // variance, or an empty shift) still need one last push at close.
        List<Shift> pendingClosedShifts = shiftRepository
            .findByBusinessIdAndStatusAndCloudSyncedAtIsNullOrderByClosedAtAsc(
                localId,
                SalesConstants.SHIFT_STATUS_CLOSED
            );

        Set<String> shiftIds = new TreeSet<>();
        pendingSales.forEach(s -> shiftIds.add(s.getShiftId()));
        pendingClosedShifts.forEach(s -> shiftIds.add(s.getId()));
        if (shiftIds.isEmpty()) {
            return new SyncPushResult(0, 0, true);
        }
        log.info("[DesktopSync] pushing {} pending sale(s) in {} shift(s) to {}",
            pendingSales.size(), shiftIds.size(), mapping.origin());

        Map<String, Shift> shiftsById = shiftRepository
            .findAllById(shiftIds)
            .stream()
            .collect(Collectors.toMap(Shift::getId, s -> s));

        ShiftSyncRequest batch = buildBatch(pendingSales, shiftsById, mapping);

        RestClient client = restClientBuilder.baseUrl(mapping.origin()).build();
        ShiftSyncAck ack = postBatch(client, mapping, batch);

        Instant syncedAt = Instant.now();
        pendingSales.forEach(s -> s.setCloudSyncedAt(syncedAt));
        saleRepository.saveAll(pendingSales);

        // A closed shift is only fully uploaded once every one of its sales is
        // acknowledged; a shift with stragglers stays pending for the next run.
        List<Shift> closedToStamp = pendingClosedShifts.stream()
            .filter(s -> saleRepository.countByShiftIdAndCloudSyncedAtIsNull(s.getId()) == 0)
            .toList();
        closedToStamp.forEach(s -> s.setCloudSyncedAt(syncedAt));
        shiftRepository.saveAll(closedToStamp);

        log.info(
            "[DesktopSync] acknowledged: {} sale(s) new, {} skipped; marked {} sale(s) and {} shift(s) synced",
            ack.salesIngested(),
            ack.salesSkipped(),
            pendingSales.size(),
            closedToStamp.size()
        );
        return new SyncPushResult(closedToStamp.size(), ack.salesIngested(), true);
    }

    private ShiftSyncRequest buildBatch(
            List<Sale> pendingSales,
            Map<String, Shift> shiftsById,
            CloudSyncSession.Session mapping) {
        Set<String> cloudStaffIds = mapping.staffIds().stream().collect(Collectors.toSet());
        String ownerUserId = mapping.ownerUserId();

        Map<String, List<Sale>> salesByShift = pendingSales.stream()
            .collect(Collectors.groupingBy(Sale::getShiftId));

        List<ShiftSyncRequest.ShiftData> data = new ArrayList<>();
        for (Shift shift : shiftsById.values()) {
            List<ShiftSyncRequest.SaleData> sales = salesByShift
                .getOrDefault(shift.getId(), List.of())
                .stream()
                .map(s -> toSaleData(s, ownerUserId, cloudStaffIds))
                .toList();

            data.add(new ShiftSyncRequest.ShiftData(
                shift.getId(),
                shift.getBranchId(),
                shift.getTillDeviceKey(),
                shift.getStatus(),
                openedByCloudId(shift, ownerUserId, cloudStaffIds),
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

    /**
     * Mirrored staff keep their cloud ids; locally-created till users fall back
     * to the owner so the shift's NOT NULL {@code opened_by} FK resolves on the
     * cloud (same rule as {@link #toSaleData} for {@code soldBy}).
     */
    private static String openedByCloudId(
            Shift shift,
            String ownerUserId,
            Set<String> cloudStaffIds) {
        return shift.getOpenedBy() != null
            && cloudStaffIds.contains(shift.getOpenedBy())
            ? shift.getOpenedBy()
            : ownerUserId;
    }

    private ShiftSyncRequest.SaleData toSaleData(
            Sale sale,
            String ownerUserId,
            Set<String> cloudStaffIds) {
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

        // Mirrored staff keep their cloud ids, so a local cashier id is already
        // a cloud id. Locally-created till users (never seen by the cloud) fall
        // back to the owner so the sale still has a valid attribution.
        String soldByCloudId = sale.getSoldBy() != null
            && cloudStaffIds.contains(sale.getSoldBy())
            ? sale.getSoldBy()
            : ownerUserId;

        return new ShiftSyncRequest.SaleData(
            sale.getId(),
            sale.getBranchId(),
            sale.getStatus(),
            sale.getIdempotencyKey(),
            sale.getGrandTotal(),
            sale.getCashReceived(),
            soldByCloudId,
            sale.getSoldAt(),
            sale.getVoidedAt(),
            sale.getVoidNotes(),
            sale.getRefundedTotal(),
            items,
            payments
        );
    }

    private ShiftSyncRequest.SaleItemData toItemData(SaleItem item) {
        // Till batches are local-only (created at stock receipt on the till);
        // the cloud has no such batches, so sending the id would violate the
        // cloud's fk_si_batch foreign key and roll back the whole upload.
        return new ShiftSyncRequest.SaleItemData(
            item.getId(),
            item.getLineIndex(),
            item.getLineKind(),
            item.getLineLabel(),
            item.getItemId(),
            null,
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
