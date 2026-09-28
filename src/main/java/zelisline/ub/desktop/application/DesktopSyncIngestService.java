package zelisline.ub.desktop.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import zelisline.ub.audit.domain.AuditEvent;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.audit.repository.AuditEventRepository;
import zelisline.ub.desktop.api.dto.AuditEventPushAck;
import zelisline.ub.desktop.api.dto.AuditEventPushRequest;
import zelisline.ub.desktop.api.dto.ShiftSyncAck;
import zelisline.ub.desktop.api.dto.ShiftSyncRequest;
import zelisline.ub.desktop.api.dto.StockReconcileSnapshot;
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
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.platform.realtime.RealtimeBridge;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.sales.domain.SaleLineKinds;
import zelisline.ub.sales.domain.SalePayment;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.sales.repository.SalePaymentRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.suppliers.application.SupplierPayoutPhoneVerificationService;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierContact;
import zelisline.ub.suppliers.repository.SupplierContactRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.purchasing.domain.RawPurchaseLine;
import zelisline.ub.purchasing.domain.RawPurchaseSession;
import zelisline.ub.purchasing.domain.SupplierInvoice;
import zelisline.ub.purchasing.domain.SupplierInvoiceLine;
import zelisline.ub.purchasing.repository.RawPurchaseLineRepository;
import zelisline.ub.purchasing.repository.RawPurchaseSessionRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceLineRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceRepository;
import zelisline.ub.purchasing.repository.StockMovementRepository;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.purchasing.domain.StockMovement;
import zelisline.ub.storefront.application.WebOrderFulfillmentService;

/**
 * Cloud-side ingest for till-uploaded shifts (the "up" direction of
 * store-and-forward sync — see {@code ShiftSyncRequest}).
 *
 * <p>Idempotent: shifts are upserted by id, and each sale is inserted only if
 * neither its id nor its {@code idempotencyKey} already exists for the
 * business. Because the whole batch runs in one transaction, a failed push
 * rolls back entirely and the till simply retries later.
 *
 * <p>v1 scope: sales and supplies are recorded directly (visible in cloud
 * reports, and sales announced in realtime to connected POS/dashboard
 * sessions) but the heavy pipelines are intentionally not re-run — no receipt
 * number allocation, no ledger journal postings, no stock deduction, no
 * customer resolution. Those are follow-ups, and the till's local copy is the
 * source of truth for its own operation.
 */
@Service
@RequiredArgsConstructor
public class DesktopSyncIngestService {

    private static final Logger log = LoggerFactory.getLogger(DesktopSyncIngestService.class);

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
    private final ItemRepository itemRepository;
    private final StockMovementRepository stockMovementRepository;
    private final WebOrderFulfillmentService webOrderFulfillmentService;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditEventRepository auditEventRepository;

    @Transactional
    public ShiftSyncAck ingest(String businessId, ShiftSyncRequest request) {
        int customersIngested = 0;
        if (request.customers() != null) {
            for (ShiftSyncRequest.CustomerData data : request.customers()) {
                upsertCustomer(businessId, data);
                customersIngested++;
            }
        }

        int suppliersIngested = 0;
        if (request.suppliers() != null) {
            for (ShiftSyncRequest.SupplierData data : request.suppliers()) {
                upsertSupplier(businessId, data);
                suppliersIngested++;
            }
        }

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
            "[DesktopSync] ingested {} customer(s), {} supplier(s), {} shift(s): {} new sale(s), {} already seen",
            customersIngested,
            suppliersIngested,
            shifts,
            salesIngested,
            salesSkipped
        );
        return new ShiftSyncAck(shifts, salesIngested, salesSkipped, suppliersIngested);
    }

    /**
     * Upsert a till-created customer so the sales below can reference it (FK
     * {@code sales.customer_id}). Phones are replaced wholesale from the till's
     * copy (it is the source of truth for customers it created); the credit
     * account carries the till's authoritative balance. A new cloud customer
     * gets the next sequential number so the cloud's numbering stays intact.
     */
    /**
     * Adopt the till's authoritative stock on the cloud (one-time reconcile).
     * For each item whose cloud count differs, set it and append a
     * {@code desktop_reconcile} adjustment movement so the change is auditable
     * and the till — which originated it — skips it on replay.
     */
    @Transactional
    public int reconcileStock(String businessId, StockReconcileSnapshot body) {
        if (body == null || body.branchId() == null || body.branchId().isBlank()
                || body.items() == null) {
            return 0;
        }
        String referenceId = java.util.UUID.randomUUID().toString();
        int changed = 0;
        for (StockReconcileSnapshot.ItemStock row : body.items()) {
            if (row.itemId() == null || row.currentStock() == null) {
                continue;
            }
            var item = itemRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(row.itemId(), businessId)
                .orElse(null);
            if (item == null) {
                continue;
            }
            java.math.BigDecimal current = item.getCurrentStock() == null
                ? java.math.BigDecimal.ZERO
                : item.getCurrentStock();
            java.math.BigDecimal delta = row.currentStock().subtract(current);
            if (delta.signum() == 0) {
                continue;
            }
            item.setCurrentStock(row.currentStock());
            itemRepository.save(item);

            StockMovement movement = new StockMovement();
            movement.setBusinessId(businessId);
            movement.setBranchId(body.branchId());
            movement.setItemId(row.itemId());
            movement.setBatchId(null);
            movement.setMovementType(InventoryConstants.MOVEMENT_ADJUSTMENT);
            movement.setReferenceType(SalesConstants.STOCK_REFERENCE_TYPE_DESKTOP_RECONCILE);
            movement.setReferenceId(referenceId);
            movement.setQuantityDelta(delta);
            movement.setNotes("Stock adopted from till (desktop reconcile)");
            stockMovementRepository.save(movement);
            changed++;
        }
        log.info("[DesktopSync] stock reconcile from till: {} item(s) adjusted", changed);
        return changed;
    }

    private void upsertCustomer(String businessId, ShiftSyncRequest.CustomerData data) {
        Customer customer = customerRepository
            .findByIdAndBusinessIdAndDeletedAtIsNull(data.id(), businessId)
            .orElseGet(() -> {
                Customer created = new Customer();
                created.setId(data.id());
                created.setBusinessId(businessId);
                created.setCustomerNo(customerRepository
                    .nextCustomerNo(businessId)
                    .map(n -> n + 1)
                    .orElse(1L));
                return created;
            });
        customer.setName(data.name());
        customer.setEmail(data.email());
        customer.setNotes(data.notes());
        // Flush BEFORE phones / credit account are queued — Hibernate does not
        // order unassociated inserts, so credit_accounts (alphabetically first)
        // would otherwise hit the customers FK before the row exists.
        customerRepository.saveAndFlush(customer);

        if (data.phones() != null) {
            // Replace the till's phone list wholesale (idempotent re-push safe).
            customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc(customer.getId())
                .forEach(p -> customerPhoneRepository.delete(p));
            for (ShiftSyncRequest.CustomerPhoneData phoneData : data.phones()) {
                // A phone may already belong to a cloud-created customer — skip
                // rather than trip uq_customer_phones_business_phone.
                if (customerPhoneRepository.existsByBusinessIdAndPhone(businessId, phoneData.phone())) {
                    continue;
                }
                CustomerPhone phone = new CustomerPhone();
                phone.setId(phoneData.id());
                phone.setBusinessId(businessId);
                phone.setCustomerId(customer.getId());
                phone.setPhone(phoneData.phone());
                phone.setPrimary(phoneData.primary());
                customerPhoneRepository.save(phone);
            }
        }

        if (data.creditAccount() != null) {
            CreditAccount acc = creditAccountRepository
                .findByCustomerIdAndBusinessId(customer.getId(), businessId)
                .orElseGet(() -> {
                    CreditAccount created = new CreditAccount();
                    created.setId(java.util.UUID.randomUUID().toString());
                    created.setBusinessId(businessId);
                    created.setCustomerId(customer.getId());
                    return created;
                });
            acc.setBalanceOwed(data.creditAccount().balanceOwed());
            if (data.creditAccount().walletBalance() != null) {
                acc.setWalletBalance(data.creditAccount().walletBalance());
            }
            acc.setLoyaltyPoints(data.creditAccount().loyaltyPoints());
            acc.setCreditLimit(data.creditAccount().creditLimit());
            if (data.creditAccount().creditSuspended() != null) {
                acc.setCreditSuspended(data.creditAccount().creditSuspended());
            }
            acc.setLastActivityAt(java.time.Instant.now());
            creditAccountRepository.save(acc);
        }
    }

    /**
     * Upsert a till-created/edited supplier id-preservingly (the cloud adopts
     * the till's id, as it does for till customers). Contacts are replaced
     * wholesale from the till's copy — the till is the source of truth for the
     * suppliers it edited (last-writer-wins per the scope's conflict rule).
     */
    private void upsertSupplier(String businessId, ShiftSyncRequest.SupplierData data) {
        Supplier supplier = supplierRepository
            .findByIdAndBusinessId(data.id(), businessId)
            .orElseGet(() -> {
                Supplier created = new Supplier();
                created.setId(data.id());
                created.setBusinessId(businessId);
                return created;
            });
        supplier.setName(data.name());
        supplier.setCode(data.code());
        supplier.setSupplierType(data.supplierType() == null ? "distributor" : data.supplierType());
        supplier.setVatPin(data.vatPin());
        supplier.setTaxExempt(data.taxExempt());
        supplier.setCreditTermsDays(data.creditTermsDays());
        supplier.setCreditLimit(data.creditLimit());
        supplier.setStatus(data.status() == null || data.status().isBlank() ? "active" : data.status());
        supplier.setNotes(data.notes());
        supplier.setPaymentMethodPreferred(data.paymentMethodPreferred());
        supplier.setPaymentDetails(data.paymentDetails());
        String prevPayoutType = supplier.getPayoutType();
        String prevPayoutPhone = supplier.getPayoutPhone();
        supplier.setPayoutType(data.payoutType() == null ? "manual" : data.payoutType());
        supplier.setPayoutPhone(data.payoutPhone());
        supplier.setPayoutTillNumber(data.payoutTillNumber());
        supplier.setPayoutPaybillNumber(data.payoutPaybillNumber());
        supplier.setPayoutPaybillAccount(data.payoutPaybillAccount());
        // Till-side edits bypass the portal/patch paths — a synced phone change must
        // not ride a stale OTP-verified flag into automated Send Money.
        SupplierPayoutPhoneVerificationService.clearIfPhoneChanged(supplier, prevPayoutPhone, prevPayoutType);
        // Flush BEFORE contacts are queued — Hibernate does not order
        // unassociated inserts, so supplier_contacts (alphabetically first)
        // would hit the suppliers FK before the row exists.
        supplierRepository.saveAndFlush(supplier);

        if (data.contacts() != null) {
            supplierContactRepository.findBySupplierIdOrderByPrimaryContactDescNameAsc(supplier.getId())
                .forEach(supplierContactRepository::delete);
            for (ShiftSyncRequest.SupplierContactData c : data.contacts()) {
                SupplierContact contact = new SupplierContact();
                contact.setId(c.id());
                contact.setSupplierId(supplier.getId());
                contact.setName(c.name());
                contact.setRoleLabel(c.roleLabel());
                contact.setPhone(c.phone());
                contact.setEmail(c.email());
                contact.setPrimaryContact(c.primary());
                supplierContactRepository.save(contact);
            }
        }
    }

    /**
     * Ingest till-recorded supplies (Path B sessions + their invoices).
     * Idempotent by session id — a retried push skips sessions the cloud has
     * already stored. Runs in one transaction like the shift ingest. Called
     * separately from {@link #ingest} (the till pushes supplies on their own
     * endpoint), after the suppliers in the same push have been upserted so
     * the {@code supplier_id} FK resolves.
     */
    /**
     * Ingest till-forwarded audit events into the shop's cloud audit log. The
     * till runs its own {@code audit_events} table; forwarding keeps the cloud's
     * unified log complete for shops that also sell online.
     *
     * <p>Idempotent by event id (Uuids are globally unique) — an event already
     * on the cloud is skipped, so a retry, or the till's cursor re-send of
     * boundary rows, never duplicates. {@code businessId} is forced from the
     * caller's session and the till's copy ignored, so a till can never write
     * into another tenant's log. A row missing a required field (or carrying an
     * unknown enum value) is skipped rather than failing the batch — a poisoned
     * row would otherwise wedge the queue.
     */
    @Transactional
    public AuditEventPushAck ingestAuditEvents(String businessId, AuditEventPushRequest request) {
        List<AuditEventPushRequest.AuditEventData> events =
            request == null || request.events() == null ? List.of() : request.events();
        if (events.isEmpty()) {
            return new AuditEventPushAck(0, 0);
        }
        List<String> ids = events.stream()
            .filter(d -> d != null && d.id() != null && !d.id().isBlank())
            .map(AuditEventPushRequest.AuditEventData::id)
            .toList();
        Set<String> seen = new HashSet<>();
        if (!ids.isEmpty()) {
            auditEventRepository.findAllById(ids).forEach(e -> seen.add(e.getId()));
        }
        int ingested = 0;
        int skipped = 0;
        for (AuditEventPushRequest.AuditEventData data : events) {
            AuditEvent event = toAuditEvent(businessId, data);
            if (event == null || !seen.add(event.getId())) {
                skipped++;
                continue;
            }
            auditEventRepository.save(event);
            ingested++;
        }
        log.info(
            "[DesktopSync] ingested {} till audit event(s), skipped {} duplicate/invalid",
            ingested, skipped);
        return new AuditEventPushAck(ingested, skipped);
    }

    /**
     * Map a forwarded row to an entity, or {@code null} when it is not
     * forwardable (missing id, or a required string/enum). Enum strings are
     * parsed leniently so a value this cloud build doesn't know is skipped, not
     * thrown.
     */
    private static AuditEvent toAuditEvent(
            String businessId, AuditEventPushRequest.AuditEventData d) {
        if (d == null || d.id() == null || d.id().isBlank()) {
            return null;
        }
        String eventType = blankToNull(d.eventType());
        AuditEventCategory category = parseEnum(AuditEventCategory.class, d.category());
        AuditEventSeverity severity = parseEnum(AuditEventSeverity.class, d.severity());
        AuditEventActorType actorType = parseEnum(AuditEventActorType.class, d.actorType());
        if (eventType == null || category == null || severity == null || actorType == null) {
            return null;
        }
        AuditEvent e = new AuditEvent();
        e.setId(d.id());
        // Forced from the authenticated session — never the till's copy.
        e.setBusinessId(businessId);
        e.setBranchId(blankToNull(d.branchId()));
        e.setCategory(category);
        e.setEventType(eventType);
        e.setSeverity(severity);
        e.setActorId(blankToNull(d.actorId()));
        e.setActorType(actorType);
        e.setActorName(blankToNull(d.actorName()));
        e.setTargetType(blankToNull(d.targetType()));
        e.setTargetId(blankToNull(d.targetId()));
        e.setTargetLabel(blankToNull(d.targetLabel()));
        e.setSessionId(blankToNull(d.sessionId()));
        e.setCorrelationId(blankToNull(d.correlationId()));
        e.setIpAddress(blankToNull(d.ipAddress()));
        e.setUserAgent(blankToNull(d.userAgent()));
        e.setSource(blankToNull(d.source()));
        e.setTerminalId(blankToNull(d.terminalId()));
        e.setShiftId(blankToNull(d.shiftId()));
        e.setOldState(blankToNull(d.oldState()));
        e.setNewState(blankToNull(d.newState()));
        e.setDiff(blankToNull(d.diff()));
        e.setReason(blankToNull(d.reason()));
        e.setMetadata(blankToNull(d.metadata()));
        e.setCreatedAt(d.createdAt());
        return e;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Transactional
    public SupplySyncAck ingestSupplies(String businessId, SupplySyncSnapshot request) {
        int ingested = 0;
        int skipped = 0;
        if (request.supplies() != null) {
            for (SupplySyncSnapshot.SupplyData data : request.supplies()) {
                if (rawPurchaseSessionRepository
                        .findByIdAndBusinessId(data.sessionId(), businessId)
                        .isPresent()) {
                    skipped++;
                    continue;
                }
                ingestSupply(businessId, data);
                ingested++;
            }
        }
        log.info(
            "[DesktopSync] ingested {} supply session(s), {} already seen",
            ingested,
            skipped
        );
        return new SupplySyncAck(ingested, skipped);
    }

    /**
     * Apply one till supply line to the cloud's stock: bump
     * {@code items.current_stock} by the usable quantity and write the audit
     * movements (receipt for usable, wastage for the loss), mirroring what the
     * cloud's own PathB posting writes.
     */
    private void applyInboundStock(
            String businessId,
            RawPurchaseSession session,
            SupplySyncSnapshot.SupplyLineData lineData,
            String postedItemId) {
        var item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(postedItemId, businessId)
            .orElse(null);
        if (item == null) {
            return;
        }
        java.math.BigDecimal unitCost = lineData.draftUnitCost();

        java.math.BigDecimal base = item.getCurrentStock() == null
            ? java.math.BigDecimal.ZERO
            : item.getCurrentStock();
        item.setCurrentStock(base.add(lineData.usableQty()));
        itemRepository.save(item);

        StockMovement receipt = new StockMovement();
        receipt.setBusinessId(businessId);
        receipt.setBranchId(session.getBranchId());
        receipt.setItemId(postedItemId);
        receipt.setBatchId(null); // till batches don't exist cloud-side
        receipt.setMovementType(PurchasingConstants.MOVEMENT_RECEIPT);
        receipt.setReferenceType(PurchasingConstants.STOCK_REF_RAW_LINE);
        receipt.setReferenceId(lineData.id());
        receipt.setQuantityDelta(lineData.usableQty());
        receipt.setUnitCost(unitCost);
        receipt.setNotes("Received at till (desktop sync, session " + session.getId() + ")");
        stockMovementRepository.save(receipt);

        if (lineData.wastageQty() != null && lineData.wastageQty().signum() > 0) {
            StockMovement wastage = new StockMovement();
            wastage.setBusinessId(businessId);
            wastage.setBranchId(session.getBranchId());
            wastage.setItemId(postedItemId);
            wastage.setBatchId(null);
            wastage.setMovementType(PurchasingConstants.MOVEMENT_WASTAGE);
            wastage.setReferenceType(PurchasingConstants.STOCK_REF_RAW_LINE);
            wastage.setReferenceId(lineData.id());
            wastage.setQuantityDelta(lineData.wastageQty());
            wastage.setUnitCost(unitCost);
            wastage.setNotes("Wastage at till receive (desktop sync)");
            stockMovementRepository.save(wastage);
        }
    }

    /** Keep a cross-side item reference only when the receiving side knows the item. */
    private String resolveKnownItem(String businessId, String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        return itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId).isPresent()
            ? itemId
            : null;
    }

    private void ingestSupply(String businessId, SupplySyncSnapshot.SupplyData data) {
        RawPurchaseSession session = new RawPurchaseSession();
        session.setId(data.sessionId());
        session.setBusinessId(businessId);
        // Suppliers are upserted earlier in the same push/pull, so the FK resolves.
        session.setSupplierId(data.supplierId());
        session.setBranchId(data.branchId());
        session.setReceivedAt(data.receivedAt());
        session.setNotes(data.notes());
        // The till's opaque draft JSON is client-local state — not carried across.
        session.setClientDraftJson(null);
        session.setStatus(data.status());
        rawPurchaseSessionRepository.save(session);

        if (data.lines() != null) {
            for (SupplySyncSnapshot.SupplyLineData lineData : data.lines()) {
                RawPurchaseLine line = new RawPurchaseLine();
                line.setId(lineData.id());
                line.setSessionId(session.getId());
                line.setSortOrder(lineData.sortOrder());
                line.setDescriptionText(lineData.descriptionText());
                line.setAmountMoney(lineData.amountMoney());
                line.setSuggestedItemId(resolveKnownItem(businessId, lineData.suggestedItemId()));
                line.setLineStatus(lineData.lineStatus());
                // fk_rpl_posted_item points at items(id): a till-created item the
                // cloud hasn't adopted yet must not roll back the whole batch.
                String postedItemId = resolveKnownItem(businessId, lineData.postedItemId());
                line.setPostedItemId(postedItemId);
                line.setUsableQty(lineData.usableQty());
                line.setWastageQty(lineData.wastageQty());
                line.setDraftQty(lineData.draftQty());
                line.setDraftUnitCost(lineData.draftUnitCost());
                line.setDraftSellPrice(lineData.draftSellPrice());
                line.setDraftExpiryDate(lineData.draftExpiryDate());
                line.setPackOptionId(lineData.packOptionId());
                // Till batches don't exist on the cloud — fk_rpl_inventory_batch
                // would roll back the whole ingest; null defensively here too.
                line.setInventoryBatchId(null);
                rawPurchaseLineRepository.save(line);

                // Stock application — exactly once by construction (this whole
                // method only runs for sessions the cloud has never seen). The
                // till already moved its own stock at receive time; here we bump
                // the cloud's item totals and write audit movements that mirror
                // the cloud's own PathB posting (receipt adds usable qty; wastage
                // is recorded but never adds stock). Unit cost is the till's draft
                // estimate — the authoritative per-line cost lives on the synced
                // invoice lines. Full inventory-batch + ledger replay stays a
                // documented follow-up — batch numbering and journal posting
                // belong to the cloud's posting pipeline.
                if (postedItemId != null
                        && lineData.usableQty() != null
                        && lineData.usableQty().signum() > 0) {
                    applyInboundStock(businessId, session, lineData, postedItemId);
                }
            }
        }

        if (data.invoice() != null) {
            SupplierInvoice invoice = new SupplierInvoice();
            invoice.setId(data.invoice().id());
            invoice.setBusinessId(businessId);
            invoice.setSupplierId(data.supplierId());
            invoice.setRawPurchaseSessionId(session.getId());
            // Till-allocated PB-#### numbers can collide with the cloud's own
            // sequence; keep the document but disambiguate rather than 409-ing
            // the whole batch.
            String invoiceNumber = data.invoice().invoiceNumber();
            if (supplierInvoiceRepository.existsByBusinessIdAndInvoiceNumber(businessId, invoiceNumber)) {
                String suffixed = invoiceNumber + "-T";
                invoice.setInvoiceNumber(suffixed.length() > 64
                    ? suffixed.substring(0, 64)
                    : suffixed);
            } else {
                invoice.setInvoiceNumber(invoiceNumber);
            }
            invoice.setInvoiceDate(data.invoice().invoiceDate());
            invoice.setDueDate(data.invoice().dueDate());
            invoice.setSubtotal(data.invoice().subtotal());
            invoice.setTaxTotal(data.invoice().taxTotal());
            invoice.setGrandTotal(data.invoice().grandTotal());
            invoice.setStatus(data.invoice().status());
            invoice.setNotes(data.invoice().notes());
            // goods_receipt_id points at a cloud-side document the till never
            // created — drop it, same as batch ids.
            invoice.setGoodsReceiptId(null);
            supplierInvoiceRepository.save(invoice);

            if (data.invoice().lines() != null) {
                for (SupplySyncSnapshot.InvoiceLineData lineData : data.invoice().lines()) {
                    SupplierInvoiceLine line = new SupplierInvoiceLine();
                    line.setId(lineData.id());
                    line.setInvoiceId(invoice.getId());
                    line.setDescription(lineData.description());
                    line.setItemId(resolveKnownItem(businessId, lineData.itemId()));
                    line.setQty(lineData.qty());
                    line.setUnitCost(lineData.unitCost());
                    line.setLineTotal(lineData.lineTotal());
                    line.setSortOrder(lineData.sortOrder());
                    line.setRawLineId(lineData.rawLineId());
                    supplierInvoiceLineRepository.save(line);
                }
            }
        }
    }

    /**
     * Ingest till-side web-order fulfillment confirmations. The cloud replays
     * each transition through its own {@link WebOrderFulfillmentService} — the
     * same code path a web-side confirmation uses — so the customer's
     * "order confirmed" notification fires exactly once, from one writer.
     * Transitions the cloud can't apply (unknown order, cloud already ahead)
     * are recorded as processed so the till stops retrying them; a transient
     * failure fails the whole batch instead, so the till keeps the confirmation
     * dirty and retries rather than losing it.
     */
    @Transactional
    public WebOrderSyncAck ingestWebOrders(String businessId, WebOrderSyncSnapshot request) {
        int applied = 0;
        int skipped = 0;
        // Ids the till may mark synced: fully applied, plus permanently
        // un-appliable ones. A transient failure is deliberately NOT listed.
        List<String> processedOrderIds = new ArrayList<>();
        if (request.orders() != null) {
            for (WebOrderSyncSnapshot.OrderData data : request.orders()) {
                if (data.id() == null || data.id().isBlank()) {
                    continue;
                }
                if (data.fulfillmentStatus() == null || data.fulfillmentStatus().isBlank()) {
                    skipped++;
                    processedOrderIds.add(data.id());
                    continue;
                }
                try {
                    webOrderFulfillmentService.advance(businessId, data.id(), data.fulfillmentStatus());
                    applied++;
                    processedOrderIds.add(data.id());
                } catch (ResponseStatusException e) {
                    // Permanent: the cloud can't apply this transition and never
                    // will on a retry (unknown order, unpaid, illegal move).
                    log.info(
                        "[DesktopSync] could not apply till fulfillment '{}' for order {}: {}",
                        data.fulfillmentStatus(), data.id(), e.getReason());
                    skipped++;
                    processedOrderIds.add(data.id());
                } catch (Exception e) {
                    // Transient: fail the whole batch so the till keeps the
                    // confirmation dirty and retries, instead of dropping it.
                    throw new ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "Web-order fulfillment sync failed for order " + data.id()
                            + ": " + e.getMessage(),
                        e
                    );
                }
            }
        }
        log.info(
            "[DesktopSync] ingested web-order confirmation(s): {} applied, {} skipped",
            applied,
            skipped
        );
        return new WebOrderSyncAck(0, applied, skipped, processedOrderIds);
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
        // Blank → null: a legacy shift (no X-Till-Device-Id) is the shared
        // branch drawer, never an empty-string key.
        shift.setTillDeviceKey(blankToNull(data.tillDeviceKey()));
        shift.setStatus(data.status());
        // shifts.opened_by is NOT NULL — without this every new shift upload
        // failed the whole ingest (sale included) on the cloud.
        shift.setOpenedBy(data.openedBy());
        shift.setOpeningCash(data.openingCash());
        shift.setExpectedClosingCash(data.expectedClosingCash());
        shift.setCountedClosingCash(data.countedClosingCash());
        if (data.cashTakenOut() != null) {
            shift.setCashTakenOut(data.cashTakenOut());
        }
        shift.setClosingVariance(data.closingVariance());
        shift.setOpeningNotes(data.openingNotes());
        shift.setClosingNotes(data.closingNotes());
        shift.setVarianceReason(data.varianceReason());
        shift.setBlindClosing(data.blindClosing());
        shift.setOpenedAt(data.openedAt());
        shift.setClosedAt(data.closedAt());
        shiftRepository.save(shift);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
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
        // Customers are upserted earlier in the same batch, so the FK resolves.
        sale.setCustomerId(data.customerId());
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
                // Only keep an item reference the cloud actually holds: the till
                // can sell an item it created offline, which has no cloud row,
                // and a raw id would trip fk_si_item and roll back the whole
                // ingest batch. Mirrors the supply path (resolveKnownItem) and
                // the pull path, which drops unmirrored item refs too.
                item.setItemId(resolveKnownItem(businessId, itemData.itemId()));
                // Till batches don't exist on the cloud — referencing one would
                // trip fk_si_batch and roll back the whole ingest. The till
                // already omits batch ids on upload; null defensively here too.
                item.setBatchId(null);
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

        // ── Cloud stock mirror ─────────────────────────────────────────
        // The till is the stock source of truth, but the cloud mirror must
        // still track its sales so the online storefront doesn't oversell it
        // (see docs/scopes/DESKTOP_APP_AUDIT_SCOPE.md §4.5). Mirror the simple,
        // batch-free pattern the supply ingest already uses: till items hold no
        // cloud batches and negative stock is allowed, so a till sale can never
        // wedge the ingest batch. Skipped for voided sales.
        if (data.voidedAt() == null && data.items() != null) {
            for (ShiftSyncRequest.SaleItemData itemData : data.items()) {
                String kind = itemData.lineKind() == null
                    ? SaleLineKinds.ITEM
                    : itemData.lineKind();
                if (itemData.itemId() == null
                        || itemData.quantity() == null
                        || !SaleLineKinds.ITEM.equals(kind)) {
                    continue;
                }
                applySaleStockDeduction(
                    businessId,
                    sale.getBranchId(),
                    sale.getId(),
                    itemData.itemId(),
                    itemData.quantity(),
                    itemData.unitCost(),
                    sale.getSoldBy()
                );
            }
        }
    }

    /**
     * Decrement the cloud's denormalized {@code current_stock} and append a
     * {@code sale} movement for a line the till already sold. Batch-free by
     * design (till items hold no cloud batches) and negative stock is allowed,
     * so a missing or stale cloud count can never reject the ingest batch. A
     * no-op when the item isn't mirrored on the cloud.
     */
    private void applySaleStockDeduction(
            String businessId,
            String branchId,
            String saleId,
            String itemId,
            java.math.BigDecimal quantity,
            java.math.BigDecimal unitCost,
            String soldBy) {
        if (quantity.signum() <= 0) {
            return;
        }
        var item = itemRepository
            .findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
            .orElse(null);
        if (item == null) {
            return;
        }
        java.math.BigDecimal base = item.getCurrentStock() == null
            ? java.math.BigDecimal.ZERO
            : item.getCurrentStock();
        item.setCurrentStock(base.subtract(quantity));
        itemRepository.save(item);

        StockMovement movement = new StockMovement();
        movement.setBusinessId(businessId);
        movement.setBranchId(branchId);
        movement.setItemId(itemId);
        movement.setBatchId(null);
        movement.setMovementType(InventoryConstants.MOVEMENT_SALE);
        movement.setReferenceType(SalesConstants.STOCK_REFERENCE_TYPE_SALE);
        movement.setReferenceId(saleId);
        movement.setQuantityDelta(quantity.negate());
        movement.setUnitCost(unitCost);
        movement.setNotes("Till sale (desktop sync)");
        movement.setCreatedBy(soldBy);
        stockMovementRepository.save(movement);
    }
}
