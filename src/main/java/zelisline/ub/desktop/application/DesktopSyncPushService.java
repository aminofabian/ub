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
import zelisline.ub.desktop.api.dto.SupplySyncAck;
import zelisline.ub.desktop.api.dto.SupplySyncSnapshot;
import zelisline.ub.desktop.api.dto.WebOrderSyncAck;
import zelisline.ub.desktop.api.dto.WebOrderSyncSnapshot;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.purchasing.domain.RawPurchaseLine;
import zelisline.ub.purchasing.domain.RawPurchaseSession;
import zelisline.ub.purchasing.domain.SupplierInvoice;
import zelisline.ub.purchasing.domain.SupplierInvoiceLine;
import zelisline.ub.purchasing.repository.RawPurchaseLineRepository;
import zelisline.ub.purchasing.repository.RawPurchaseSessionRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceLineRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceRepository;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.repository.WebOrderLineRepository;
import zelisline.ub.storefront.repository.WebOrderRepository;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.sales.domain.SalePayment;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.sales.repository.SalePaymentRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierContact;
import zelisline.ub.suppliers.repository.SupplierContactRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;

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
    private final CustomerRepository customerRepository;
    private final CustomerPhoneRepository customerPhoneRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final SupplierRepository supplierRepository;
    private final SupplierContactRepository supplierContactRepository;
    private final RawPurchaseSessionRepository rawPurchaseSessionRepository;
    private final RawPurchaseLineRepository rawPurchaseLineRepository;
    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final SupplierInvoiceLineRepository supplierInvoiceLineRepository;
    private final WebOrderRepository webOrderRepository;
    private final WebOrderLineRepository webOrderLineRepository;
    private final CloudSyncSession cloudSyncSession;
    private final RestClient.Builder restClientBuilder;

    @Value("${app.desktop.business-id:}")
    private String desktopBusinessId;

    public record SyncPushResult(
            int shiftsPushed, int salesPushed, int suppliesPushed,
            int orderConfirmationsPushed, boolean configured) {}

    public SyncPushResult pushPending() {
        String localId = desktopBusinessId == null ? "" : desktopBusinessId.trim();
        CloudSyncSession.Session mapping = cloudSyncSession
            .load()
            .orElse(null);
        if (mapping == null || localId.isEmpty()) {
            log.info("[DesktopSync] no cloud mapping — nothing to push.");
            return new SyncPushResult(0, 0, 0, 0, false);
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
        // Customers created or edited on the till since the last upload — pushed
        // in the SAME batch as the sales that reference them so the cloud's
        // sales.customer_id FK always resolves, whatever triggered the flush.
        List<Customer> dirtyCustomers = customerRepository.findDirtyForDesktopSync(localId);
        // Same dirty-tracking pattern for the supplier directory.
        List<Supplier> dirtySuppliers = supplierRepository.findDirtyForDesktopSync(localId);

        Set<String> shiftIds = new TreeSet<>();
        pendingSales.forEach(s -> shiftIds.add(s.getShiftId()));
        pendingClosedShifts.forEach(s -> shiftIds.add(s.getId()));
        if (shiftIds.isEmpty() && dirtyCustomers.isEmpty() && dirtySuppliers.isEmpty()) {
            int supplies = pushSupplies(localId, mapping);
            int confirmations = pushWebOrderConfirmations(localId, mapping);
            return new SyncPushResult(0, 0, supplies, confirmations, true);
        }
        log.info(
            "[DesktopSync] pushing {} pending sale(s) in {} shift(s), {} customer(s) and {} supplier(s) to {}",
            pendingSales.size(), shiftIds.size(), dirtyCustomers.size(), dirtySuppliers.size(), mapping.origin());

        Map<String, Shift> shiftsById = shiftRepository
            .findAllById(shiftIds)
            .stream()
            .collect(Collectors.toMap(Shift::getId, s -> s));

        ShiftSyncRequest batch = buildBatch(pendingSales, shiftsById, dirtyCustomers, dirtySuppliers, mapping);

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

        dirtyCustomers.forEach(c -> c.setCloudSyncedAt(syncedAt));
        customerRepository.saveAll(dirtyCustomers);

        dirtySuppliers.forEach(s -> s.setCloudSyncedAt(syncedAt));
        supplierRepository.saveAll(dirtySuppliers);

        log.info(
            "[DesktopSync] acknowledged: {} sale(s) new, {} skipped; marked {} sale(s), "
                + "{} shift(s), {} customer(s) and {} supplier(s) synced",
            ack.salesIngested(),
            ack.salesSkipped(),
            pendingSales.size(),
            closedToStamp.size(),
            dirtyCustomers.size(),
            dirtySuppliers.size()
        );

        // Supplies are pushed after the shifts batch so any suppliers upserted
        // above are already on the cloud and the raw_purchase_sessions.supplier_id
        // FK resolves even for a brand-new till-created supplier.
        int suppliesPushed = pushSupplies(localId, mapping);
        // Till-side order confirmations last: the cloud replays them through its
        // own fulfillment service (which notifies the customer).
        int confirmationsPushed = pushWebOrderConfirmations(localId, mapping);
        return new SyncPushResult(
            closedToStamp.size(), ack.salesIngested(), suppliesPushed, confirmationsPushed, true);
    }

    /**
     * Push till-side web-order confirmations (a cashier tapping "confirm" on a
     * paid online order). The cloud ingests them by replaying the transition
     * through its own fulfillment service, so the customer notification comes
     * from the same code path a web-side confirmation uses. Idempotent by
     * order id + transition; the till stamps {@code cloud_synced_at} on ack so
     * confirmed orders aren't re-pushed every flush.
     *
     * @return number of confirmations the cloud accepted
     */
    private int pushWebOrderConfirmations(String localId, CloudSyncSession.Session mapping) {
        List<WebOrder> dirtyOrders = webOrderRepository.findDirtyForDesktopSync(localId);
        if (dirtyOrders.isEmpty()) {
            return 0;
        }
        log.info(
            "[DesktopSync] pushing {} web order update(s) to {}",
            dirtyOrders.size(), mapping.origin());

        List<WebOrderSyncSnapshot.OrderData> data = dirtyOrders.stream()
            .map(this::toWebOrderData)
            .toList();

        RestClient client = restClientBuilder.baseUrl(mapping.origin()).build();
        WebOrderSyncAck ack = postWebOrders(client, mapping, new WebOrderSyncSnapshot(data));

        Instant syncedAt = Instant.now();
        dirtyOrders.forEach(o -> o.setCloudSyncedAt(syncedAt));
        webOrderRepository.saveAll(dirtyOrders);

        log.info(
            "[DesktopSync] acknowledged: {} web order confirmation(s) applied, {} skipped",
            ack.confirmationsApplied(),
            ack.confirmationsSkipped()
        );
        return ack.confirmationsApplied();
    }

    /** Order + its lines (context for the cloud's fulfillment transition). */
    private WebOrderSyncSnapshot.OrderData toWebOrderData(WebOrder order) {
        List<WebOrderSyncSnapshot.LineData> lines = webOrderLineRepository
            .findByOrderIdOrderByLineIndexAsc(order.getId())
            .stream()
            .map(l -> new WebOrderSyncSnapshot.LineData(
                l.getId(),
                l.getItemId(),
                l.getItemName(),
                l.getVariantName(),
                l.getQuantity(),
                l.getUnitPrice(),
                l.getLineTotal(),
                l.getLineIndex()))
            .toList();
        return new WebOrderSyncSnapshot.OrderData(
            order.getId(),
            order.getCode(),
            order.getChannel(),
            order.getCatalogBranchId(),
            order.getStatus(),
            order.getFulfillmentStatus(),
            order.getCurrency(),
            order.getGrandTotal(),
            order.getCustomerName(),
            order.getCustomerPhone(),
            order.getCustomerEmail(),
            order.getNotes(),
            order.getPaidAt(),
            order.getCreatedAt(),
            order.getUpdatedAt(),
            order.getPickupTicketPrintedAt(),
            order.getExpiresAt(),
            lines
        );
    }

    private WebOrderSyncAck postWebOrders(
            RestClient client,
            CloudSyncSession.Session session,
            WebOrderSyncSnapshot batch) {
        try {
            return doPostWebOrders(client, session, batch);
        } catch (Exception e) {
            if (!isUnauthorized(e)) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not upload order confirmations to the online shop (" + e.getMessage() + ")"
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
                return doPostWebOrders(client, refreshed, batch);
            } catch (Exception e2) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not upload order confirmations to the online shop (" + e2.getMessage() + ")"
                );
            }
        }
    }

    private WebOrderSyncAck doPostWebOrders(
            RestClient client,
            CloudSyncSession.Session session,
            WebOrderSyncSnapshot batch) {
        WebOrderSyncAck ack = client
            .post()
            .uri("/api/v1/desktop/sync/web-orders")
            .header("Authorization", "Bearer " + session.accessToken())
            .header("X-Tenant-Id", session.cloudBusinessId())
            .contentType(MediaType.APPLICATION_JSON)
            .body(batch)
            .retrieve()
            .body(WebOrderSyncAck.class);
        if (ack == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "The online shop returned an empty acknowledgment"
            );
        }
        return ack;
    }

    private ShiftSyncRequest buildBatch(
            List<Sale> pendingSales,
            Map<String, Shift> shiftsById,
            List<Customer> dirtyCustomers,
            List<Supplier> dirtySuppliers,
            CloudSyncSession.Session mapping) {
        Set<String> cloudStaffIds = mapping.staffIds().stream().collect(Collectors.toSet());
        String ownerUserId = mapping.ownerUserId();

        List<ShiftSyncRequest.CustomerData> customerData = dirtyCustomers.stream()
            .map(this::toCustomerData)
            .toList();

        List<ShiftSyncRequest.SupplierData> supplierData = dirtySuppliers.stream()
            .map(this::toSupplierData)
            .toList();

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
                blankToNull(shift.getTillDeviceKey()),
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
        return new ShiftSyncRequest(data, customerData, supplierData);
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

    /** Legacy shifts (no {@code X-Till-Device-Id} at open) upload a null key, never blank. */
    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
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
            sale.getCustomerId(),
            sale.getSoldAt(),
            sale.getVoidedAt(),
            sale.getVoidNotes(),
            sale.getRefundedTotal(),
            items,
            payments
        );
    }

    /** Customer + phones + live credit state, as the till's authoritative copy. */
    private ShiftSyncRequest.CustomerData toCustomerData(Customer customer) {
        List<ShiftSyncRequest.CustomerPhoneData> phones = customerPhoneRepository
            .findByCustomerIdOrderByCreatedAtAsc(customer.getId())
            .stream()
            .map(p -> new ShiftSyncRequest.CustomerPhoneData(
                p.getId(), p.getPhone(), p.isPrimary()))
            .toList();
        ShiftSyncRequest.CreditAccountData credit = creditAccountRepository
            .findByCustomerIdAndBusinessId(customer.getId(), customer.getBusinessId())
            .map(a -> new ShiftSyncRequest.CreditAccountData(
                a.getBalanceOwed(),
                a.getWalletBalance(),
                a.getLoyaltyPoints(),
                a.getCreditLimit()))
            .orElse(null);
        return new ShiftSyncRequest.CustomerData(
            customer.getId(),
            customer.getName(),
            customer.getEmail(),
            customer.getNotes(),
            phones,
            credit
        );
    }

    /** Supplier + contacts, as the till's authoritative copy (last-writer-wins). */
    private ShiftSyncRequest.SupplierData toSupplierData(Supplier supplier) {
        List<ShiftSyncRequest.SupplierContactData> contacts = supplierContactRepository
            .findBySupplierIdOrderByPrimaryContactDescNameAsc(supplier.getId())
            .stream()
            .map(c -> new ShiftSyncRequest.SupplierContactData(
                c.getId(), c.getName(), c.getRoleLabel(), c.getPhone(), c.getEmail(),
                c.isPrimaryContact()))
            .toList();
        return new ShiftSyncRequest.SupplierData(
            supplier.getId(),
            supplier.getName(),
            supplier.getCode(),
            supplier.getSupplierType(),
            supplier.getVatPin(),
            supplier.isTaxExempt(),
            supplier.getCreditTermsDays(),
            supplier.getCreditLimit(),
            supplier.getStatus(),
            supplier.getNotes(),
            supplier.getPaymentMethodPreferred(),
            supplier.getPaymentDetails(),
            supplier.getPayoutType(),
            supplier.getPayoutPhone(),
            supplier.getPayoutTillNumber(),
            supplier.getPayoutPaybillNumber(),
            supplier.getPayoutPaybillAccount(),
            contacts
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
     * Push till-recorded supplies (posted Path B sessions + their invoices) to
     * the cloud. Idempotent: the cloud skips session ids it already stored, and
     * only then does the till stamp {@code cloud_synced_at}. The sync scheduler
     * runs this AFTER the shifts/customers/suppliers batch in the same flush,
     * so referenced suppliers always exist cloud-side first.
     *
     * @return number of sessions the cloud accepted as new
     */
    private int pushSupplies(String localId, CloudSyncSession.Session mapping) {
        List<RawPurchaseSession> dirtySupplies =
            rawPurchaseSessionRepository.findDirtyForDesktopSync(localId);
        if (dirtySupplies.isEmpty()) {
            return 0;
        }
        log.info(
            "[DesktopSync] pushing {} supply session(s) to {}",
            dirtySupplies.size(), mapping.origin());

        List<SupplySyncSnapshot.SupplyData> data = dirtySupplies.stream()
            .map(this::toSupplyData)
            .toList();

        RestClient client = restClientBuilder.baseUrl(mapping.origin()).build();
        SupplySyncAck ack = postSupplies(client, mapping, new SupplySyncSnapshot(data));

        Instant syncedAt = Instant.now();
        dirtySupplies.forEach(s -> s.setCloudSyncedAt(syncedAt));
        rawPurchaseSessionRepository.saveAll(dirtySupplies);

        log.info(
            "[DesktopSync] acknowledged: {} supply session(s) new, {} skipped",
            ack.sessionsIngested(),
            ack.sessionsSkipped()
        );
        return ack.sessionsIngested();
    }

    /** Session + raw lines + resulting invoice, as the till's authoritative copy. */
    private SupplySyncSnapshot.SupplyData toSupplyData(RawPurchaseSession session) {
        List<SupplySyncSnapshot.SupplyLineData> lines = rawPurchaseLineRepository
            .findBySessionIdOrderBySortOrderAscIdAsc(session.getId())
            .stream()
            .map(l -> new SupplySyncSnapshot.SupplyLineData(
                l.getId(),
                l.getSortOrder(),
                l.getDescriptionText(),
                l.getAmountMoney(),
                l.getSuggestedItemId(),
                l.getLineStatus(),
                l.getPostedItemId(),
                l.getUsableQty(),
                l.getWastageQty(),
                l.getDraftQty(),
                l.getDraftUnitCost(),
                l.getDraftSellPrice(),
                l.getDraftExpiryDate(),
                l.getPackOptionId()))
            .toList();
        // Double-posts can leave more than one invoice on a session; the newest
        // one is the authoritative document.
        List<SupplierInvoice> invoices = supplierInvoiceRepository
            .findByRawPurchaseSessionIdOrderByCreatedAtDesc(session.getId());
        SupplierInvoice invoice = invoices.isEmpty() ? null : invoices.get(0);
        List<SupplySyncSnapshot.InvoiceLineData> invoiceLines = invoice == null
            ? null
            : supplierInvoiceLineRepository
                .findByInvoiceIdOrderBySortOrderAsc(invoice.getId())
                .stream()
                .map(DesktopSyncPushService::toInvoiceLineData)
                .toList();
        return new SupplySyncSnapshot.SupplyData(
            session.getId(),
            session.getSupplierId(),
            session.getBranchId(),
            session.getReceivedAt(),
            session.getStatus(),
            session.getNotes(),
            session.getUpdatedAt(),
            lines,
            invoice == null ? null : new SupplySyncSnapshot.InvoiceData(
                invoice.getId(),
                invoice.getInvoiceNumber(),
                invoice.getInvoiceDate(),
                invoice.getDueDate(),
                invoice.getSubtotal(),
                invoice.getTaxTotal(),
                invoice.getGrandTotal(),
                invoice.getStatus(),
                invoice.getNotes(),
                invoiceLines
            )
        );
    }

    private static SupplySyncSnapshot.InvoiceLineData toInvoiceLineData(SupplierInvoiceLine l) {
        return new SupplySyncSnapshot.InvoiceLineData(
            l.getId(),
            l.getDescription(),
            l.getItemId(),
            l.getQty(),
            l.getUnitCost(),
            l.getLineTotal(),
            l.getSortOrder(),
            l.getRawLineId());
    }

    private SupplySyncAck postSupplies(
            RestClient client,
            CloudSyncSession.Session session,
            SupplySyncSnapshot batch) {
        try {
            return doPostSupplies(client, session, batch);
        } catch (Exception e) {
            if (!isUnauthorized(e)) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not upload supplies to the online shop (" + e.getMessage() + ")"
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
                return doPostSupplies(client, refreshed, batch);
            } catch (Exception e2) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not upload supplies to the online shop (" + e2.getMessage() + ")"
                );
            }
        }
    }

    private SupplySyncAck doPostSupplies(
            RestClient client,
            CloudSyncSession.Session session,
            SupplySyncSnapshot batch) {
        SupplySyncAck ack = client
            .post()
            .uri("/api/v1/desktop/sync/supplies")
            .header("Authorization", "Bearer " + session.accessToken())
            .header("X-Tenant-Id", session.cloudBusinessId())
            .contentType(MediaType.APPLICATION_JSON)
            .body(batch)
            .retrieve()
            .body(SupplySyncAck.class);
        if (ack == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "The online shop returned an empty acknowledgment"
            );
        }
        return ack;
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
