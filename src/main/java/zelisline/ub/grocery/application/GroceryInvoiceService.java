package zelisline.ub.grocery.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.data.domain.PageRequest;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.application.ItemSellability;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.grocery.GroceryConstants;
import zelisline.ub.grocery.api.dto.CancelGroceryInvoiceRequest;
import zelisline.ub.grocery.api.dto.CreateGroceryInvoiceLineRequest;
import zelisline.ub.grocery.api.dto.CreateGroceryInvoiceRequest;
import zelisline.ub.grocery.api.dto.GroceryInvoiceLineResponse;
import zelisline.ub.grocery.api.dto.GroceryInvoiceListResponse;
import zelisline.ub.grocery.api.dto.GroceryInvoiceResponse;
import zelisline.ub.grocery.api.dto.GroceryInvoiceSummaryResponse;
import zelisline.ub.grocery.api.dto.GroceryTopProductResponse;
import zelisline.ub.grocery.api.dto.PayGroceryInvoiceRequest;
import zelisline.ub.grocery.api.dto.PayGroceryInvoiceResponse;
import zelisline.ub.grocery.api.dto.RemoteInvoiceStkResponse;
import zelisline.ub.grocery.domain.GroceryInvoice;
import zelisline.ub.grocery.domain.GroceryInvoiceLine;
import zelisline.ub.grocery.repository.GroceryInvoiceLineRepository;
import zelisline.ub.grocery.repository.GroceryInvoiceRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.messaging.application.CreditSaleReminderLineItem;
import zelisline.ub.payments.application.GatewayStkPushService;
import zelisline.ub.payments.application.PaymentGatewayStkService;
import zelisline.ub.payments.application.StkPushRetryHelper;
import zelisline.ub.payments.domain.GatewayStkPushStatuses;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.StkPushContextType;
import zelisline.ub.platform.realtime.RealtimeBridge;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.api.dto.PostSaleLineRequest;
import zelisline.ub.sales.api.dto.PostSalePaymentRequest;
import zelisline.ub.sales.api.dto.PostSaleRequest;
import zelisline.ub.sales.application.OpenShiftResolver;
import zelisline.ub.sales.application.SaleActorNameService;
import zelisline.ub.sales.application.SaleCreationOutcome;
import zelisline.ub.sales.application.SaleService;
import zelisline.ub.tenancy.repository.BranchRepository;

@Service
@RequiredArgsConstructor
public class GroceryInvoiceService {

    private static final Logger log = LoggerFactory.getLogger(GroceryInvoiceService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int BARCODE_RANDOM_LENGTH = 10;
    private static final int MONEY_SCALE = 2;
    private static final int QTY_SCALE = 4;

    private final GroceryInvoiceRepository invoiceRepository;
    private final GroceryInvoiceLineRepository lineRepository;
    private final ItemRepository itemRepository;
    private final ItemCatalogService itemCatalogService;
    private final BranchRepository branchRepository;
    private final SaleService saleService;
    private final OpenShiftResolver openShiftResolver;
    private final SaleActorNameService saleActorNameService;
    private final ApplicationEventPublisher eventPublisher;
    private final StkPushRetryHelper stkPushRetryHelper;
    private final GatewayStkPushService gatewayStkPushService;
    private final UserRepository userRepository;

    @Transactional
    public GroceryInvoiceResponse createInvoice(
            String businessId,
            CreateGroceryInvoiceRequest request,
            String userId
    ) {
        branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(request.branchId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));

        List<GroceryInvoiceLine> lineEntities = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;

        int lineIndex = 0;
        for (CreateGroceryInvoiceLineRequest lineReq : request.lines()) {
            Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(lineReq.itemId(), businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Item not found: " + lineReq.itemId()));

            String sellabilityViolation = ItemSellability.sellabilityViolation(item);
            if (sellabilityViolation != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, sellabilityViolation);
            }

            BigDecimal qty = normalizeInvoiceQuantity(item, lineReq.quantity(), lineIndex + 1);
            BigDecimal unitPrice = lineReq.unitPrice().setScale(QTY_SCALE, RoundingMode.HALF_UP);
            BigDecimal lineTotal = qty.multiply(unitPrice).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

            GroceryInvoiceLine line = new GroceryInvoiceLine();
            line.setItemId(item.getId());
            line.setItemName(item.getName());
            line.setLineIndex(lineIndex);
            line.setQuantity(qty);
            line.setUnitName(lineReq.unitName() != null && !lineReq.unitName().isBlank()
                    ? lineReq.unitName() : "each");
            line.setUnitPrice(unitPrice);
            line.setLineTotal(lineTotal);
            lineEntities.add(line);

            subtotal = subtotal.add(lineTotal);
            lineIndex++;
        }

        BigDecimal grandTotal = subtotal.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        String barcode = generateUniqueBarcode(businessId);

        GroceryInvoice invoice = new GroceryInvoice();
        invoice.setBusinessId(businessId);
        invoice.setBranchId(request.branchId());
        invoice.setStatus(GroceryConstants.STATUS_PENDING_PAYMENT);
        invoice.setBarcodeCode(barcode);
        invoice.setSubtotal(subtotal);
        invoice.setGrandTotal(grandTotal);
        invoice.setCreatedBy(userId);
        invoice.setExpiresAt(Instant.now().plus(GroceryConstants.DEFAULT_EXPIRY_HOURS, ChronoUnit.HOURS));
        invoice.setNotes(request.notes());

        boolean remote = Boolean.TRUE.equals(request.remote());
        if (remote) {
            String phone = normalizeRemotePhone(request.customerPhone());
            if (phone == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Customer phone is required for remote invoices");
            }
            invoice.setRemote(true);
            invoice.setCustomerPhone(phone);
            if (request.customerId() != null && !request.customerId().isBlank()) {
                invoice.setCustomerId(request.customerId().trim());
            }
        }

        invoice = invoiceRepository.save(invoice);

        for (GroceryInvoiceLine line : lineEntities) {
            line.setInvoiceId(invoice.getId());
        }
        List<GroceryInvoiceLine> savedLines = lineRepository.saveAll(lineEntities);

        GroceryInvoiceResponse response = toResponse(invoice, savedLines);

        try {
            String createdByName = saleActorNameService.resolveSoldByName(businessId, userId);
            eventPublisher.publishEvent(new RealtimeBridge.GroceryInvoiceCreatedEvent(
                    businessId, request.branchId(), invoice.getId(),
                    barcode, grandTotal, lineEntities.size(), userId, createdByName,
                    invoice.isRemote(), invoice.getCustomerPhone()));
        } catch (Exception e) {
            log.warn("Failed to publish grocery invoice created event for {}", invoice.getId(), e);
        }

        if (remote) {
            List<CreditSaleReminderLineItem> notifyItems = new ArrayList<>();
            for (GroceryInvoiceLine line : savedLines) {
                notifyItems.add(new CreditSaleReminderLineItem(
                        line.getItemName() != null ? line.getItemName() : "Item",
                        line.getQuantity(),
                        line.getLineTotal()));
            }
            eventPublisher.publishEvent(new RemoteGroceryInvoiceNotifyEvent(
                    businessId,
                    invoice.getId(),
                    invoice.getBranchId(),
                    invoice.getCustomerPhone(),
                    invoice.getBarcodeCode(),
                    invoice.getGrandTotal(),
                    savedLines.size(),
                    List.copyOf(notifyItems)));
            try {
                initiateRemoteStk(businessId, invoice);
                invoice = invoiceRepository.findById(invoice.getId()).orElse(invoice);
                response = toResponse(invoice, savedLines);
            } catch (Exception e) {
                log.warn("Remote invoice {} created but STK failed: {}", invoice.getId(), e.getMessage());
            }
        }

        return response;
    }

    @Transactional(readOnly = true)
    public GroceryInvoiceResponse getInvoice(String businessId, String invoiceId) {
        GroceryInvoice invoice = loadInvoiceOrThrow(businessId, invoiceId);
        List<GroceryInvoiceLine> lines = lineRepository.findByInvoiceIdOrderByLineIndex(invoiceId);
        return toResponse(invoice, lines);
    }

    @Transactional(readOnly = true)
    public GroceryInvoiceResponse getInvoiceByBarcode(String businessId, String barcode) {
        GroceryInvoice invoice = invoiceRepository.findByBarcodeCodeAndBusinessId(barcode, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
        List<GroceryInvoiceLine> lines = lineRepository.findByInvoiceIdOrderByLineIndex(invoice.getId());
        return toResponse(invoice, lines);
    }

    @Transactional(readOnly = true)
    public GroceryInvoiceListResponse listInvoices(String businessId, String branchId, String status) {
        return listInvoices(businessId, branchId, status, null);
    }

    /**
     * Variant of {@link #listInvoices(String, String, String)} that, when
     * {@code createdByFilter} is non-blank, restricts results to invoices
     * created by that user. Used to scope a {@code grocery_clerk} to the
     * invoices they themselves generated.
     */
    @Transactional(readOnly = true)
    public GroceryInvoiceListResponse listInvoices(
            String businessId,
            String branchId,
            String status,
            String createdByFilter
    ) {
        boolean ownOnly = createdByFilter != null && !createdByFilter.isBlank();
        boolean filterStatus = status != null && !status.isBlank();
        List<GroceryInvoice> invoices;
        if (filterStatus && ownOnly) {
            invoices = invoiceRepository.findByBusinessIdAndBranchIdAndStatusAndCreatedByOrderByCreatedAtDesc(
                    businessId, branchId, status, createdByFilter);
        } else if (filterStatus) {
            invoices = invoiceRepository.findByBusinessIdAndBranchIdAndStatusOrderByCreatedAtDesc(
                    businessId, branchId, status);
        } else if (ownOnly) {
            invoices = invoiceRepository.findByBusinessIdAndBranchIdAndCreatedByOrderByCreatedAtDesc(
                    businessId, branchId, createdByFilter);
        } else {
            invoices = invoiceRepository.findByBusinessIdAndBranchIdOrderByCreatedAtDesc(
                    businessId, branchId);
        }

        List<GroceryInvoiceSummaryResponse> summaries = new ArrayList<>();
        for (GroceryInvoice invoice : invoices) {
            List<GroceryInvoiceLine> lines = lineRepository.findByInvoiceIdOrderByLineIndex(invoice.getId());
            String createdByName = saleActorNameService.resolveSoldByName(businessId, invoice.getCreatedBy());
            String lockedByName = saleActorNameService.resolveSoldByName(businessId, invoice.getLockedBy());
            summaries.add(new GroceryInvoiceSummaryResponse(
                    invoice.getId(),
                    invoice.getBarcodeCode(),
                    invoice.getStatus(),
                    invoice.getGrandTotal(),
                    lines.size(),
                    invoice.getCreatedBy(),
                    createdByName,
                    invoice.getCreatedAt(),
                    invoice.getExpiresAt(),
                    invoice.getLockedBy(),
                    lockedByName,
                    invoice.isRemote(),
                    invoice.getCustomerPhone(),
                    invoice.getCustomerId(),
                    invoice.getLastStkStatus(),
                    invoice.getLastStkAt()
            ));
        }

        return new GroceryInvoiceListResponse(summaries);
    }

    /**
     * Top items the given user has invoiced from the given branch, ranked by
     * distinct invoice count (then total quantity, then recency). Used by the
     * grocery counter's "Top sellers" panel so it survives page reloads —
     * sorting happens at the database, not in the browser.
     *
     * <p>Cancelled invoices are excluded so a clerk's mistakes don't pollute
     * their personal top sellers.</p>
     */
    @Transactional(readOnly = true)
    public List<GroceryTopProductResponse> topProductsForUser(
            String businessId,
            String branchId,
            String userId,
            int limit
    ) {
        if (businessId == null || businessId.isBlank()
                || branchId == null || branchId.isBlank()
                || userId == null || userId.isBlank()) {
            return List.of();
        }
        int bounded = Math.max(1, Math.min(limit, 100));
        List<Object[]> rows = lineRepository.topItemsForUser(
                businessId, branchId, userId, PageRequest.of(0, bounded));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> itemIds = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            itemIds.add((String) row[0]);
        }
        Map<String, Item> itemsById = new LinkedHashMap<>();
        for (Item item : itemRepository.findAllById(itemIds)) {
            if (businessId.equals(item.getBusinessId())) {
                itemsById.put(item.getId(), item);
            }
        }
        Map<String, String> thumbs = itemCatalogService.resolveThumbnailUrls(businessId, itemIds);

        List<GroceryTopProductResponse> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            String itemId = (String) row[0];
            Item item = itemsById.get(itemId);
            if (item == null) {
                continue;
            }
            String name = item.getName() != null ? item.getName() : (String) row[1];
            String sku = item.getSku();
            String thumb = thumbs.get(itemId);
            long invoiceCount = ((Number) row[2]).longValue();
            BigDecimal totalQty = row[3] instanceof BigDecimal bd
                    ? bd
                    : BigDecimal.valueOf(((Number) row[3]).doubleValue());
            Instant lastAt = (Instant) row[4];
            out.add(new GroceryTopProductResponse(
                    itemId,
                    name,
                    sku,
                    thumb,
                    invoiceCount,
                    totalQty,
                    lastAt
            ));
        }
        return out;
    }

    @Transactional
    public GroceryInvoiceResponse cancelInvoice(
            String businessId,
            String invoiceId,
            CancelGroceryInvoiceRequest request,
            String userId
    ) {
        GroceryInvoice invoice = loadInvoiceOrThrow(businessId, invoiceId);

        if (!invoice.isPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only pending invoices can be cancelled. Current status: " + invoice.getStatus());
        }

        invoice.setStatus(GroceryConstants.STATUS_CANCELLED);
        invoice.setCancelledBy(userId);
        invoice.setCancelledAt(Instant.now());
        invoice.setCancelledReason(request.reason());
        invoice.setLockedBy(null);
        invoice.setLockedAt(null);
        invoice.setLockExpiresAt(null);
        invoiceRepository.save(invoice);

        List<GroceryInvoiceLine> lines = lineRepository.findByInvoiceIdOrderByLineIndex(invoiceId);
        GroceryInvoiceResponse resp = toResponse(invoice, lines);

        try {
            eventPublisher.publishEvent(new RealtimeBridge.GroceryInvoiceCancelledEvent(
                    businessId, invoice.getBranchId(), invoice.getId(), invoice.getBarcodeCode()));
        } catch (Exception e) {
            log.warn("Failed to publish grocery invoice cancelled event for {}", invoiceId, e);
        }

        return resp;
    }

    @Transactional
    public PayGroceryInvoiceResponse payInvoice(
            String businessId,
            String invoiceId,
            PayGroceryInvoiceRequest request,
            String userId,
            String roleId
    ) {
        return payInvoice(businessId, invoiceId, request, userId, roleId, null);
    }

    @Transactional
    public PayGroceryInvoiceResponse payInvoice(
            String businessId,
            String invoiceId,
            PayGroceryInvoiceRequest request,
            String userId,
            String roleId,
            String tillDeviceKey
    ) {
        GroceryInvoice invoice = loadInvoiceOrThrow(businessId, invoiceId);

        if (!invoice.isPending()) {
            if (invoice.isPaid()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Invoice already paid");
            }
            if (invoice.isCancelled()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Invoice has been cancelled");
            }
            if (invoice.isExpired()) {
                throw new ResponseStatusException(HttpStatus.GONE, "Invoice has expired");
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Invoice cannot be paid. Current status: " + invoice.getStatus());
        }

        if (invoice.getExpiresAt().isBefore(Instant.now())) {
            invoice.setStatus(GroceryConstants.STATUS_EXPIRED);
            invoiceRepository.save(invoice);
            throw new ResponseStatusException(HttpStatus.GONE, "Invoice has expired");
        }

        openShiftResolver
                .findOpen(businessId, invoice.getBranchId(), tillDeviceKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No open shift for this branch"));

        List<GroceryInvoiceLine> invoiceLines = lineRepository.findByInvoiceIdOrderByLineIndex(invoiceId);
        List<PostSaleLineRequest> additionalLines = request.additionalLines() == null
                ? List.of()
                : request.additionalLines();

        List<String> itemIds = new ArrayList<>();
        for (GroceryInvoiceLine line : invoiceLines) {
            itemIds.add(line.getItemId());
        }
        for (PostSaleLineRequest extra : additionalLines) {
            if (extra.itemId() != null && !extra.itemId().isBlank()) {
                itemIds.add(extra.itemId());
            }
        }
        Map<String, Item> itemsById = itemRepository
                .findByIdInAndBusinessIdAndDeletedAtIsNull(itemIds.stream().distinct().toList(), businessId)
                .stream()
                .collect(java.util.stream.Collectors.toMap(Item::getId, item -> item, (a, b) -> a));
        List<PostSaleLineRequest> saleLines = new ArrayList<>();
        for (GroceryInvoiceLine line : invoiceLines) {
            Item item = itemsById.get(line.getItemId());
            if (item == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Item not found: " + line.getItemId());
            }
            BigDecimal saleQty = normalizeInvoiceQuantity(item, line.getQuantity(), line.getLineIndex() + 1);
            saleLines.add(PostSaleLineRequest.catalogItem(
                    line.getItemId(),
                    saleQty,
                    line.getUnitPrice()
            ));
        }
        int extraIndex = 0;
        for (PostSaleLineRequest extra : additionalLines) {
            extraIndex++;
            Item item = itemsById.get(extra.itemId());
            if (item == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Additional line item not found: " + extra.itemId());
            }
            BigDecimal saleQty = normalizeInvoiceQuantity(item, extra.quantity(), invoiceLines.size() + extraIndex);
            BigDecimal unitPrice = extra.unitPrice() == null
                    ? BigDecimal.ZERO
                    : extra.unitPrice().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            saleLines.add(PostSaleLineRequest.catalogItem(
                    extra.itemId(),
                    saleQty,
                    unitPrice
            ));
        }

        String customerId = blankToNull(request.customerId());
        if (customerId == null) {
            customerId = blankToNull(invoice.getCustomerId());
        } else if (blankToNull(invoice.getCustomerId()) == null) {
            invoice.setCustomerId(customerId);
        }

        BigDecimal expectedTotal = BigDecimal.ZERO;
        for (PostSaleLineRequest saleLine : saleLines) {
            expectedTotal = expectedTotal.add(
                    saleLine.quantity()
                            .multiply(saleLine.unitPrice())
                            .setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        }
        expectedTotal = expectedTotal.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        BigDecimal paymentSum = BigDecimal.ZERO;
        if (request.payments() != null) {
            for (var payment : request.payments()) {
                if (payment.amount() != null) {
                    paymentSum = paymentSum.add(
                            payment.amount().setScale(MONEY_SCALE, RoundingMode.HALF_UP));
                }
            }
        }
        paymentSum = paymentSum.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (paymentSum.compareTo(expectedTotal) < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Payments must cover line totals (paid "
                            + paymentSum.toPlainString()
                            + ", invoice needs "
                            + expectedTotal.toPlainString()
                            + ")");
        }

        PostSaleRequest saleRequest = new PostSaleRequest(
                invoice.getBranchId(),
                saleLines,
                request.payments(),
                null,
                customerId,
                null
        );

        String idempotencyKey = "grocery:" + invoice.getId() + ":" + UUID.randomUUID().toString().substring(0, 8);
        SaleCreationOutcome outcome = saleService.createSale(
                businessId, idempotencyKey, saleRequest, userId, roleId, tillDeviceKey);

        invoice.setStatus(GroceryConstants.STATUS_PAID);
        invoice.setPaidBy(userId);
        invoice.setPaidAt(Instant.now());
        invoice.setSaleId(outcome.response().id());
        invoice.setLockedBy(null);
        invoice.setLockedAt(null);
        invoice.setLockExpiresAt(null);
        invoiceRepository.save(invoice);

        try {
            String paidByName = saleActorNameService.resolveSoldByName(businessId, userId);
            eventPublisher.publishEvent(new RealtimeBridge.GroceryInvoicePaidEvent(
                    businessId, invoice.getBranchId(), invoice.getId(),
                    invoice.getBarcodeCode(), outcome.response().id(), userId, paidByName));
        } catch (Exception e) {
            log.warn("Failed to publish grocery invoice paid event for {}", invoiceId, e);
        }

        try {
            BigDecimal confirmedTotal = outcome.response().grandTotal() != null
                    ? outcome.response().grandTotal()
                    : invoice.getGrandTotal();
            eventPublisher.publishEvent(new RealtimeBridge.PaymentConfirmedEvent(
                    businessId,
                    invoice.getBranchId(),
                    outcome.response().id(),
                    confirmedTotal,
                    "grocery_invoice",
                    userId));
        } catch (Exception e) {
            log.warn("Failed to publish payment confirmed event for invoice {}", invoiceId, e);
        }

        return new PayGroceryInvoiceResponse(
                invoice.getId(),
                outcome.response().id(),
                GroceryConstants.STATUS_PAID,
                invoice.getPaidAt(),
                outcome.response()
        );
    }

    @Transactional
    public GroceryInvoiceResponse lockInvoice(String businessId, String invoiceId, String userId) {
        GroceryInvoice invoice = loadInvoiceOrThrow(businessId, invoiceId);

        if (!invoice.isPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only pending invoices can be locked. Current status: " + invoice.getStatus());
        }

        // If already locked by this user, extend the lock
        if (invoice.getLockedBy() != null && invoice.getLockedBy().equals(userId)) {
            invoice.setLockExpiresAt(Instant.now().plus(GroceryConstants.LOCK_EXPIRY_MINUTES, ChronoUnit.MINUTES));
            invoiceRepository.save(invoice);
            List<GroceryInvoiceLine> lines = lineRepository.findByInvoiceIdOrderByLineIndex(invoiceId);
            return toResponse(invoice, lines);
        }

        // If locked by someone else and lock hasn't expired
        if (invoice.getLockedBy() != null
                && invoice.getLockExpiresAt() != null
                && invoice.getLockExpiresAt().isAfter(Instant.now())) {
            String lockerName = saleActorNameService.resolveSoldByName(businessId, invoice.getLockedBy());
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Invoice is currently being processed by " + lockerName);
        }

        // Acquire lock
        invoice.setLockedBy(userId);
        invoice.setLockedAt(Instant.now());
        invoice.setLockExpiresAt(Instant.now().plus(GroceryConstants.LOCK_EXPIRY_MINUTES, ChronoUnit.MINUTES));
        invoiceRepository.save(invoice);

        List<GroceryInvoiceLine> lines = lineRepository.findByInvoiceIdOrderByLineIndex(invoiceId);
        GroceryInvoiceResponse resp = toResponse(invoice, lines);

        try {
            String lockedByName = saleActorNameService.resolveSoldByName(businessId, userId);
            eventPublisher.publishEvent(new RealtimeBridge.GroceryInvoiceLockedEvent(
                    businessId, invoice.getBranchId(), invoice.getId(),
                    invoice.getBarcodeCode(), userId, lockedByName));
        } catch (Exception e) {
            log.warn("Failed to publish grocery invoice locked event for {}", invoiceId, e);
        }

        return resp;
    }

    @Transactional
    public GroceryInvoiceResponse unlockInvoice(String businessId, String invoiceId, String userId) {
        GroceryInvoice invoice = loadInvoiceOrThrow(businessId, invoiceId);

        if (invoice.getLockedBy() == null) {
            List<GroceryInvoiceLine> lines = lineRepository.findByInvoiceIdOrderByLineIndex(invoiceId);
            return toResponse(invoice, lines);
        }

        if (!invoice.getLockedBy().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Invoice is locked by another user");
        }

        invoice.setLockedBy(null);
        invoice.setLockedAt(null);
        invoice.setLockExpiresAt(null);
        invoiceRepository.save(invoice);

        List<GroceryInvoiceLine> lines = lineRepository.findByInvoiceIdOrderByLineIndex(invoiceId);
        GroceryInvoiceResponse resp = toResponse(invoice, lines);

        try {
            eventPublisher.publishEvent(new RealtimeBridge.GroceryInvoiceUnlockedEvent(
                    businessId, invoice.getBranchId(), invoice.getId(), invoice.getBarcodeCode()));
        } catch (Exception e) {
            log.warn("Failed to publish grocery invoice unlocked event for {}", invoiceId, e);
        }

        return resp;
    }

    @Transactional
    public int expireInvoices() {
        Instant now = Instant.now();
        List<GroceryInvoice> expired = invoiceRepository.findByStatusAndExpiresAtBefore(
                GroceryConstants.STATUS_PENDING_PAYMENT, now);

        int count = 0;
        for (GroceryInvoice invoice : expired) {
            invoice.setStatus(GroceryConstants.STATUS_EXPIRED);
            invoice.setLockedBy(null);
            invoice.setLockedAt(null);
            invoice.setLockExpiresAt(null);
            invoiceRepository.save(invoice);
            count++;

            try {
                eventPublisher.publishEvent(new RealtimeBridge.GroceryInvoiceExpiredEvent(
                        invoice.getBusinessId(), invoice.getBranchId(),
                        invoice.getId(), invoice.getBarcodeCode()));
            } catch (Exception e) {
                log.warn("Failed to publish grocery invoice expired event for {}", invoice.getId(), e);
            }
        }

        if (count > 0) {
            log.info("Expired {} stale grocery invoices", count);
        }
        return count;
    }

    // ---- helpers ----

    /**
     * Weighed lines keep fractional qty; non-weighed must be whole numbers so sale posting succeeds.
     */
    private static BigDecimal normalizeInvoiceQuantity(Item item, BigDecimal quantity, int lineNumber) {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Line " + lineNumber + ": quantity must be positive");
        }
        if (item.isWeighed()) {
            // Sale API allows at most 3 decimal places on weighed qty.
            return quantity.setScale(3, RoundingMode.HALF_UP);
        }
        BigDecimal stripped = quantity.stripTrailingZeros();
        if (stripped.scale() > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Line " + lineNumber + ": non-weighed items must have a whole-number quantity");
        }
        return stripped.setScale(0, RoundingMode.UNNECESSARY);
    }

    private GroceryInvoice loadInvoiceOrThrow(String businessId, String invoiceId) {
        return invoiceRepository.findByIdAndBusinessId(invoiceId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String generateUniqueBarcode(String businessId) {
        String barcode;
        do {
            barcode = GroceryConstants.BARCODE_PREFIX + generateRandomAlphanumeric(BARCODE_RANDOM_LENGTH);
        } while (invoiceRepository.findByBarcodeCodeAndBusinessId(barcode, businessId).isPresent());
        return barcode;
    }

    private static String generateRandomAlphanumeric(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHANUMERIC.charAt(SECURE_RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return sb.toString();
    }

    GroceryInvoiceResponse toResponse(GroceryInvoice invoice, List<GroceryInvoiceLine> lines) {
        String businessId = invoice.getBusinessId();

        List<GroceryInvoiceLineResponse> lineResponses = new ArrayList<>();
        for (GroceryInvoiceLine line : lines) {
            lineResponses.add(new GroceryInvoiceLineResponse(
                    line.getId(),
                    line.getItemId(),
                    line.getItemName(),
                    line.getLineIndex(),
                    line.getQuantity(),
                    line.getUnitName(),
                    line.getUnitPrice(),
                    line.getLineTotal()
            ));
        }

        String createdByName = saleActorNameService.resolveSoldByName(businessId, invoice.getCreatedBy());
        String cancelledByName = saleActorNameService.resolveSoldByName(businessId, invoice.getCancelledBy());
        String paidByName = saleActorNameService.resolveSoldByName(businessId, invoice.getPaidBy());
        String lockedByName = saleActorNameService.resolveSoldByName(businessId, invoice.getLockedBy());

        return new GroceryInvoiceResponse(
                invoice.getId(),
                invoice.getBarcodeCode(),
                invoice.getStatus(),
                invoice.getBranchId(),
                invoice.getSubtotal(),
                invoice.getGrandTotal(),
                lineResponses,
                invoice.getNotes(),
                invoice.getExpiresAt(),
                invoice.getCreatedBy(),
                createdByName,
                invoice.getCreatedAt(),
                invoice.getCancelledBy(),
                cancelledByName,
                invoice.getCancelledAt(),
                invoice.getCancelledReason(),
                invoice.getPaidBy(),
                paidByName,
                invoice.getPaidAt(),
                invoice.getSaleId(),
                invoice.getLockedBy(),
                lockedByName,
                invoice.getLockedAt(),
                invoice.getLockExpiresAt(),
                invoice.isRemote(),
                invoice.getCustomerPhone(),
                invoice.getCustomerId(),
                invoice.getLastStkStatus(),
                invoice.getLastStkAt()
        );
    }

    private static String normalizeRemotePhone(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return zelisline.ub.payments.application.StkPhoneNormalizer.normalize(raw);
    }

    @Transactional
    public RemoteInvoiceStkResponse resendRemoteStk(String businessId, String invoiceId) {
        GroceryInvoice invoice = loadInvoiceOrThrow(businessId, invoiceId);
        if (!invoice.isRemote()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invoice is not a remote bill");
        }
        if (!invoice.isPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invoice is not awaiting payment");
        }
        if (invoice.getExpiresAt().isBefore(Instant.now())) {
            invoice.setStatus(GroceryConstants.STATUS_EXPIRED);
            invoiceRepository.save(invoice);
            throw new ResponseStatusException(HttpStatus.GONE, "Invoice has expired");
        }
        return initiateRemoteStk(businessId, invoice);
    }

    /**
     * Called when GROCERY_INVOICE STK succeeds. Idempotent if already paid.
     *
     * @return true if auto-settled (or already paid), false if cashier-confirm mode left it pending
     */
    @Transactional
    public boolean settleRemoteInvoiceFromStk(
            String businessId,
            String invoiceId,
            String gatewayTxnId,
            boolean autoSettle
    ) {
        GroceryInvoice invoice = invoiceRepository.findByIdAndBusinessId(invoiceId, businessId).orElse(null);
        if (invoice == null || !invoice.isRemote()) {
            return false;
        }
        invoice.setLastStkStatus(GatewayStkPushStatuses.SUCCESS);
        invoice.setLastStkAt(Instant.now());
        invoiceRepository.save(invoice);

        eventPublisher.publishEvent(new RealtimeBridge.GroceryInvoiceStkEvent(
                businessId,
                invoice.getBranchId(),
                invoice.getId(),
                invoice.getBarcodeCode(),
                GatewayStkPushStatuses.SUCCESS,
                invoice.getCustomerPhone(),
                invoice.getGrandTotal()));

        if (invoice.isPaid()) {
            return true;
        }
        if (!invoice.isPending()) {
            return false;
        }
        if (!autoSettle) {
            return false;
        }

        String userId = invoice.getCreatedBy();
        String roleId = userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(userId, businessId)
                .map(u -> u.getRoleId())
                .orElse(null);

        String reference = gatewayTxnId != null && !gatewayTxnId.isBlank() ? gatewayTxnId.trim() : "STK";
        PayGroceryInvoiceRequest payReq = new PayGroceryInvoiceRequest(List.of(
                new PostSalePaymentRequest(
                        SalesConstants.PAYMENT_METHOD_MPESA_MANUAL,
                        invoice.getGrandTotal(),
                        reference)), null, null);
        try {
            payInvoice(businessId, invoiceId, payReq, userId, roleId);
            return true;
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT
                    && invoiceRepository.findByIdAndBusinessId(invoiceId, businessId)
                    .map(GroceryInvoice::isPaid)
                    .orElse(false)) {
                return true;
            }
            throw e;
        }
    }

    @Transactional
    public void markRemoteStkFailed(String businessId, String invoiceId, String reason) {
        GroceryInvoice invoice = invoiceRepository.findByIdAndBusinessId(invoiceId, businessId).orElse(null);
        if (invoice == null || !invoice.isRemote() || !invoice.isPending()) {
            return;
        }
        invoice.setLastStkStatus(GatewayStkPushStatuses.FAILED);
        invoice.setLastStkAt(Instant.now());
        invoiceRepository.save(invoice);
        eventPublisher.publishEvent(new RealtimeBridge.GroceryInvoiceStkEvent(
                businessId,
                invoice.getBranchId(),
                invoice.getId(),
                invoice.getBarcodeCode(),
                GatewayStkPushStatuses.FAILED,
                invoice.getCustomerPhone(),
                invoice.getGrandTotal()));
        log.info("Remote invoice STK failed invoice={} reason={}", invoiceId, reason);
    }

    private RemoteInvoiceStkResponse initiateRemoteStk(String businessId, GroceryInvoice invoice) {
        String phone = invoice.getCustomerPhone();
        if (phone == null || phone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invoice has no customer phone");
        }
        String reference = "gi-" + invoice.getId().replace("-", "").substring(0, 16)
                + "-" + UUID.randomUUID().toString().substring(0, 6);
        String description = "Bill " + invoice.getBarcodeCode();

        PaymentGatewayStkService.StkPushOutcome outcome = stkPushRetryHelper.initiateAfterClearingPhone(
                businessId,
                null,
                phone,
                invoice.getGrandTotal(),
                reference,
                description);

        if (outcome.accepted() && outcome.checkoutRequestId() != null) {
            GatewayType gatewayType = GatewayType.valueOf(outcome.gatewayType());
            gatewayStkPushService.registerPush(
                    businessId,
                    gatewayType,
                    outcome.configId(),
                    outcome.checkoutRequestId(),
                    reference,
                    StkPushContextType.GROCERY_INVOICE,
                    invoice.getId(),
                    invoice.getGrandTotal(),
                    phone);
            invoice.setLastStkStatus(GatewayStkPushStatuses.PENDING);
            invoice.setLastStkAt(Instant.now());
            invoiceRepository.save(invoice);
            eventPublisher.publishEvent(new RealtimeBridge.GroceryInvoiceStkEvent(
                    businessId,
                    invoice.getBranchId(),
                    invoice.getId(),
                    invoice.getBarcodeCode(),
                    GatewayStkPushStatuses.PENDING,
                    phone,
                    invoice.getGrandTotal()));
        } else {
            invoice.setLastStkStatus(GatewayStkPushStatuses.FAILED);
            invoice.setLastStkAt(Instant.now());
            invoiceRepository.save(invoice);
        }

        return new RemoteInvoiceStkResponse(
                invoice.getId(),
                outcome.checkoutRequestId(),
                outcome.accepted() ? GatewayStkPushStatuses.PENDING : GatewayStkPushStatuses.FAILED,
                outcome.message(),
                outcome.accepted());
    }
}
