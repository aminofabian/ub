package zelisline.ub.desktop.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import zelisline.ub.catalog.domain.Category;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.domain.ItemImage;
import zelisline.ub.catalog.domain.ItemType;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemImageRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.desktop.api.dto.CloudSalesSnapshot;
import zelisline.ub.desktop.api.dto.MasterDataSnapshot;
import zelisline.ub.desktop.api.dto.MessageReplyPushAck;
import zelisline.ub.desktop.api.dto.MessageReplyPushRequest;
import zelisline.ub.desktop.api.dto.MessageSyncSnapshot;
import zelisline.ub.desktop.api.dto.ShiftSyncAck;
import zelisline.ub.desktop.api.dto.ShiftSyncRequest;
import zelisline.ub.desktop.api.dto.SupplySyncAck;
import zelisline.ub.desktop.api.dto.SupplySyncSnapshot;
import zelisline.ub.desktop.api.dto.WebOrderSyncAck;
import zelisline.ub.desktop.api.dto.WebOrderSyncSnapshot;
import zelisline.ub.desktop.application.DesktopSyncIngestService;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.messages.api.dto.ContactMessageReplyRequest;
import zelisline.ub.messages.api.dto.ContactMessageReplyResponse;
import zelisline.ub.messages.application.ContactMessageService;
import zelisline.ub.messages.domain.ContactMessage;
import zelisline.ub.messages.domain.ContactMessageReply;
import zelisline.ub.messages.repository.ContactMessageReplyRepository;
import zelisline.ub.messages.repository.ContactMessageRepository;
import zelisline.ub.pricing.domain.TaxRate;
import zelisline.ub.pricing.repository.TaxRateRepository;
import zelisline.ub.purchasing.domain.RawPurchaseLine;
import zelisline.ub.purchasing.domain.RawPurchaseSession;
import zelisline.ub.purchasing.domain.SupplierInvoice;
import zelisline.ub.purchasing.domain.SupplierInvoiceLine;
import zelisline.ub.purchasing.repository.RawPurchaseLineRepository;
import zelisline.ub.purchasing.repository.RawPurchaseSessionRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceLineRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceRepository;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.sales.domain.SalePayment;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.sales.repository.SalePaymentRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.domain.WebOrderLine;
import zelisline.ub.storefront.repository.WebOrderLineRepository;
import zelisline.ub.storefront.repository.WebOrderRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierContact;
import zelisline.ub.suppliers.repository.SupplierContactRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Master-data export consumed by the desktop "connect" flow
 * (see {@code DesktopConnectService}).
 *
 * <p>Authenticated like any other API call: the caller must hold a valid JWT
 * for the business, and the tenant is resolved from the standard
 * {@code X-Tenant-Id} / host resolution — so a desktop client that signs in
 * with the shop owner's credentials can pull the snapshot with the same
 * headers the web app uses.
 */
@RestController
@RequestMapping("/api/v1/desktop/sync")
@RequiredArgsConstructor
public class DesktopSyncController {

    /** Storefront customer roles that must not be mirrored onto the till. */
    private static final java.util.Set<String> NON_TILL_ROLE_KEYS = java.util.Set.of("buyer");

    private final BusinessRepository businessRepository;
    private final BranchRepository branchRepository;
    private final CategoryRepository categoryRepository;
    private final ItemRepository itemRepository;
    private final ItemImageRepository itemImageRepository;
    private final ItemTypeRepository itemTypeRepository;
    private final TaxRateRepository taxRateRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final DesktopSyncIngestService ingestService;
    private final SaleRepository saleRepository;
    private final SaleItemRepository saleItemRepository;
    private final SalePaymentRepository salePaymentRepository;
    private final ShiftRepository shiftRepository;
    private final CustomerRepository customerRepository;
    private final CustomerPhoneRepository customerPhoneRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final ContactMessageService contactMessageService;
    private final ContactMessageReplyRepository contactMessageReplyRepository;
    private final ContactMessageRepository contactMessageRepository;
    private final SupplierRepository supplierRepository;
    private final SupplierContactRepository supplierContactRepository;
    private final RawPurchaseSessionRepository rawPurchaseSessionRepository;
    private final RawPurchaseLineRepository rawPurchaseLineRepository;
    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final SupplierInvoiceLineRepository supplierInvoiceLineRepository;
    private final WebOrderRepository webOrderRepository;
    private final WebOrderLineRepository webOrderLineRepository;

    private static final Logger log = LoggerFactory.getLogger(DesktopSyncController.class);

    @GetMapping("/master-data")
    public MasterDataSnapshot masterData(HttpServletRequest request) {
        String businessId = TenantRequestIds.resolveBusinessId(request);
        Business business = businessRepository
            .findByIdAndDeletedAtIsNull(businessId)
            .orElseThrow(() ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Business not found"
                )
            );

        List<Item> items = itemRepository.findByBusinessIdAndDeletedAtIsNull(businessId);
        List<String> itemIds = items.stream().map(Item::getId).toList();
        List<ItemImage> images = itemIds.isEmpty()
            ? List.of()
            : itemImageRepository.findByItemIdIn(
                itemIds,
                Sort.by("itemId").and(Sort.by("sortOrder")).and(Sort.by("id"))
            );

        return new MasterDataSnapshot(
            new MasterDataSnapshot.BusinessData(
                business.getId(),
                business.getName(),
                business.getSlug(),
                business.getCurrency(),
                business.getCountryCode(),
                business.getTimezone(),
                business.getSettings(),
                business.getSubscriptionTier(),
                business.getSubscriptionBillingStatus() == null
                    ? null
                    : business.getSubscriptionBillingStatus().name()
            ),
            branchRepository
                .findByBusinessIdAndDeletedAtIsNullOrderByNameAsc(businessId)
                .stream()
                .map(DesktopSyncController::toBranch)
                .toList(),
            categoryRepository
                .findByBusinessIdOrderByPositionAsc(businessId)
                .stream()
                .map(DesktopSyncController::toCategory)
                .toList(),
            items.stream().map(DesktopSyncController::toItem).toList(),
            taxRateRepository
                .findByBusinessIdAndActiveIsTrueOrderByNameAsc(businessId)
                .stream()
                .map(DesktopSyncController::toTaxRate)
                .toList(),
            itemTypeRepository
                .findByBusinessIdOrderBySortOrderAsc(businessId)
                .stream()
                .map(DesktopSyncController::toItemType)
                .toList(),
            userRepository
                .findByBusinessIdAndDeletedAtIsNull(businessId)
                .stream()
                .map(this::toStaff)
                .filter(s -> s.roleKey() == null || !NON_TILL_ROLE_KEYS.contains(s.roleKey()))
                .toList(),
            images.stream().map(DesktopSyncController::toImage).toList(),
            supplierRepository
                .findAllByBusinessIdNotDeleted(businessId)
                .stream()
                .map(this::toSupplier)
                .toList(),
            // Tombstones bounded to the last 90 days so the list stays small
            // while still catching a till that syncs monthly.
            supplierRepository.findDeletedIdsSince(
                businessId, Instant.now().minus(java.time.Duration.ofDays(90)))
        );
    }

    private static MasterDataSnapshot.BranchData toBranch(Branch b) {
        return new MasterDataSnapshot.BranchData(
            b.getId(),
            b.getName(),
            b.getAddress(),
            b.getReceiptSettings(),
            b.isActive()
        );
    }

    private static MasterDataSnapshot.CategoryData toCategory(Category c) {
        return new MasterDataSnapshot.CategoryData(
            c.getId(),
            c.getName(),
            c.getSlug(),
            c.getDescription(),
            c.getParentId(),
            c.getPosition(),
            c.getDefaultTaxRateId(),
            c.getDefaultMarkupPct(),
            c.isActive()
        );
    }

    private static MasterDataSnapshot.ItemData toItem(Item i) {
        return new MasterDataSnapshot.ItemData(
            i.getId(),
            i.getSku(),
            i.getBarcode(),
            i.getPluCode(),
            i.getName(),
            i.getDescription(),
            i.getCategoryId(),
            i.getUnitType(),
            i.isStocked(),
            i.getCurrentStock(),
            i.getPackagingUnitName(),
            i.getPackagingUnitQty(),
            i.getBundlePrice(),
            i.getBuyingPrice(),
            i.getMinStockLevel(),
            i.getVariantOfItemId(),
            i.getVariantName(),
            i.isActive(),
            i.getItemTypeId()
        );
    }

    private static MasterDataSnapshot.ItemTypeData toItemType(ItemType t) {
        return new MasterDataSnapshot.ItemTypeData(
            t.getId(),
            t.getTypeKey(),
            t.getLabel(),
            t.getIcon(),
            t.getColor(),
            t.getSortOrder(),
            t.isActive(),
            t.isDefault()
        );
    }

    private static MasterDataSnapshot.TaxRateData toTaxRate(TaxRate t) {
        return new MasterDataSnapshot.TaxRateData(
            t.getId(),
            t.getName(),
            t.getRatePercent(),
            t.isInclusive(),
            t.isActive()
        );
    }

    private MasterDataSnapshot.StaffData toStaff(User u) {
        String roleKey = u.getRoleId() == null
            ? null
            : roleRepository.findByIdAndDeletedAtIsNull(u.getRoleId())
                .map(zelisline.ub.identity.domain.Role::getRoleKey)
                .orElse(null);
        return new MasterDataSnapshot.StaffData(
            u.getId(),
            u.getBranchId(),
            u.getName(),
            u.getEmail(),
            u.getPhone(),
            u.getStatus(),
            roleKey
        );
    }

    private static MasterDataSnapshot.ImageData toImage(ItemImage img) {
        return new MasterDataSnapshot.ImageData(
            img.getId(),
            img.getItemId(),
            img.getContentType(),
            img.getSortOrder(),
            img.getFormat(),
            img.getSecureUrl(),
            img.getAltText(),
            img.getWidth(),
            img.getHeight(),
            img.getBytes()
        );
    }

    private MasterDataSnapshot.SupplierData toSupplier(Supplier s) {
        return new MasterDataSnapshot.SupplierData(
            s.getId(),
            s.getName(),
            s.getCode(),
            s.getSupplierType(),
            s.getVatPin(),
            s.isTaxExempt(),
            s.getCreditTermsDays(),
            s.getCreditLimit(),
            s.getStatus(),
            s.getNotes(),
            s.getPaymentMethodPreferred(),
            s.getPaymentDetails(),
            s.getPayoutType(),
            s.getPayoutPhone(),
            s.getPayoutTillNumber(),
            s.getPayoutPaybillNumber(),
            s.getPayoutPaybillAccount(),
            s.getPrepaymentBalance(),
            "active".equalsIgnoreCase(s.getStatus()),
            supplierContactRepository
                .findBySupplierIdOrderByPrimaryContactDescNameAsc(s.getId())
                .stream()
                .map(DesktopSyncController::toSupplierContact)
                .toList()
        );
    }

    private static MasterDataSnapshot.SupplierContactData toSupplierContact(SupplierContact c) {
        return new MasterDataSnapshot.SupplierContactData(
            c.getId(),
            c.getName(),
            c.getRoleLabel(),
            c.getPhone(),
            c.getEmail(),
            c.isPrimaryContact()
        );
    }

    /**
     * Ingest till-uploaded shifts (the "up" direction of sync). Idempotent —
     * see {@link DesktopSyncIngestService}.
     */
    @PostMapping("/shifts")
    public ShiftSyncAck ingestShifts(
            @Valid @RequestBody ShiftSyncRequest request,
            HttpServletRequest http) {
        String businessId = TenantRequestIds.resolveBusinessId(http);
        if ((request.shifts() == null || request.shifts().isEmpty())
                && (request.customers() == null || request.customers().isEmpty())
                && (request.suppliers() == null || request.suppliers().isEmpty())) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Batch is empty — provide at least one shift, customer, or supplier"
            );
        }
        return ingestService.ingest(businessId, request);
    }

    /**
     * Cloud → till sales pull (the "down" direction of sync): every sale made
     * at/after {@code since} (web POS, other tills), with items + payments, so
     * the till can mirror remote sales into its local database. Idempotent on
     * the till side (it skips ids it already has).
     */
    @GetMapping("/sales")
    public CloudSalesSnapshot cloudSales(
            @RequestParam(defaultValue = "1970-01-01T00:00:00Z") String since,
            HttpServletRequest request) {
        String businessId = TenantRequestIds.resolveBusinessId(request);
        Instant cursor = parseCursor(since);
        List<Sale> sales = saleRepository
            .findByBusinessIdAndSoldAtGreaterThanEqualOrderBySoldAtAsc(
                businessId, cursor, PageRequest.of(0, 500));

        // Batch-load shifts so each sale carries its shift's openedAt (the till
        // needs it to create the placeholder shift the FK requires).
        List<String> shiftIds = sales.stream().map(Sale::getShiftId).distinct().toList();
        Map<String, Shift> shifts = shiftIds.isEmpty()
            ? Map.of()
            : shiftRepository.findAllById(shiftIds).stream()
                .collect(Collectors.toMap(Shift::getId, s -> s));

        List<CloudSalesSnapshot.CloudSaleData> data = sales.stream()
            .map(s -> toCloudSale(s, shifts.get(s.getShiftId())))
            .toList();
        List<CloudSalesSnapshot.CloudCustomerData> customers = loadCloudCustomers(businessId);
        return new CloudSalesSnapshot(data, customers);
    }

    /**
     * Cloud → till message pull: the shop's TENANT-scope Talk to Us messages
     * active strictly after {@code since} — created after it, or holding a reply
     * created after it (activity cursor). Ordered by activity ascending, each
     * carrying its full reply thread, so the till pages through and advances its
     * cursor to the newest activity timestamp seen with no gaps.
     */
    @GetMapping("/messages")
    public MessageSyncSnapshot syncMessages(
            @RequestParam(defaultValue = "1970-01-01T00:00:00Z") String since,
            @RequestParam(defaultValue = "100") int limit,
            HttpServletRequest request) {
        String businessId = TenantRequestIds.resolveBusinessId(request);
        Instant cursor = parseCursor(since);
        int capped = Math.max(1, Math.min(limit, 200));
        List<String> ids = contactMessageRepository.findActiveSinceIds(businessId, cursor, capped);
        if (ids.isEmpty()) {
            return new MessageSyncSnapshot(List.of());
        }
        Map<String, List<ContactMessageReply>> repliesByMessage = contactMessageReplyRepository
            .findByContactMessageIdInOrderByCreatedAtAsc(ids)
            .stream()
            .collect(Collectors.groupingBy(ContactMessageReply::getContactMessageId));
        List<MessageSyncSnapshot.MessageSyncData> data = contactMessageRepository
            .findAllById(ids)
            .stream()
            .sorted(Comparator.comparing(ContactMessage::getCreatedAt))
            .map(m -> toMessageData(m, repliesByMessage.getOrDefault(m.getId(), List.of())))
            .toList();
        return new MessageSyncSnapshot(data);
    }

    private static MessageSyncSnapshot.MessageSyncData toMessageData(
            ContactMessage m, List<ContactMessageReply> replies) {
        return new MessageSyncSnapshot.MessageSyncData(
            m.getId(),
            m.getName(),
            m.getEmail(),
            m.getPhone(),
            m.getBody(),
            m.getStatus().name(),
            m.getReadAt(),
            m.getSourcePath(),
            m.getCreatedAt(),
            replies.stream().map(DesktopSyncController::toReplyData).toList());
    }

    private static MessageSyncSnapshot.ReplySyncData toReplyData(ContactMessageReply r) {
        return new MessageSyncSnapshot.ReplySyncData(
            r.getId(),
            r.getChannel().name(),
            r.getBody(),
            r.getOutcome(),
            r.getDetail(),
            r.getSentByUserId(),
            r.getCreatedAt());
    }

    /** Live customer directory for the till: name/phones + current credit state. */
    private List<CloudSalesSnapshot.CloudCustomerData> loadCloudCustomers(String businessId) {
        List<Customer> customers = customerRepository.findByBusinessIdAndDeletedAtIsNull(businessId);
        List<String> ids = customers.stream().map(Customer::getId).toList();
        Map<String, List<CustomerPhone>> phones = ids.isEmpty()
            ? Map.of()
            : customerPhoneRepository.findByCustomerIdIn(ids).stream()
                .collect(Collectors.groupingBy(CustomerPhone::getCustomerId));
        Map<String, CreditAccount> credit = ids.isEmpty()
            ? Map.of()
            : creditAccountRepository.findByCustomerIdIn(ids).stream()
                .collect(Collectors.toMap(CreditAccount::getCustomerId, a -> a));
        return customers.stream()
            .map(c -> toCloudCustomer(c, phones.getOrDefault(c.getId(), List.of()), credit.get(c.getId())))
            .toList();
    }

    private static CloudSalesSnapshot.CloudCustomerData toCloudCustomer(
            Customer customer,
            List<CustomerPhone> phones,
            CreditAccount creditAccount) {
        List<CloudSalesSnapshot.CloudCustomerPhoneData> phoneData = phones.stream()
            .map(p -> new CloudSalesSnapshot.CloudCustomerPhoneData(
                p.getId(), p.getPhone(), p.isPrimary()))
            .toList();
        CloudSalesSnapshot.CloudCreditAccountData creditData = creditAccount == null
            ? null
            : new CloudSalesSnapshot.CloudCreditAccountData(
                creditAccount.getBalanceOwed(),
                creditAccount.getWalletBalance(),
                creditAccount.getLoyaltyPoints(),
                creditAccount.getCreditLimit());
        return new CloudSalesSnapshot.CloudCustomerData(
            customer.getId(),
            customer.getName(),
            customer.getEmail(),
            customer.getNotes(),
            phoneData,
            creditData
        );
    }

    private CloudSalesSnapshot.CloudSaleData toCloudSale(Sale sale, Shift shift) {
        List<CloudSalesSnapshot.CloudSaleItemData> items = saleItemRepository
            .findBySaleIdOrderByLineIndexAsc(sale.getId())
            .stream()
            .map(DesktopSyncController::toCloudItem)
            .toList();
        List<CloudSalesSnapshot.CloudSalePaymentData> payments = salePaymentRepository
            .findBySaleIdOrderBySortOrderAsc(sale.getId())
            .stream()
            .map(DesktopSyncController::toCloudPayment)
            .toList();
        return new CloudSalesSnapshot.CloudSaleData(
            sale.getId(),
            sale.getBranchId(),
            sale.getShiftId(),
            shift != null ? shift.getOpenedAt() : null,
            sale.getStatus(),
            sale.getIdempotencyKey(),
            sale.getGrandTotal(),
            sale.getCashReceived(),
            sale.getSoldBy(),
            sale.getCustomerId(),
            sale.getSoldAt(),
            sale.getVoidedAt(),
            sale.getVoidNotes(),
            sale.getRefundedTotal(),
            sale.getReceiptNo(),
            items,
            payments
        );
    }

    private static CloudSalesSnapshot.CloudSaleItemData toCloudItem(SaleItem item) {
        return new CloudSalesSnapshot.CloudSaleItemData(
            item.getId(),
            item.getLineIndex(),
            item.getLineKind(),
            item.getLineLabel(),
            item.getItemId(),
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

    private static CloudSalesSnapshot.CloudSalePaymentData toCloudPayment(SalePayment payment) {
        return new CloudSalesSnapshot.CloudSalePaymentData(
            payment.getId(),
            payment.getMethod(),
            payment.getAmount(),
            payment.getReference(),
            payment.getSortOrder()
        );
    }

    private static Instant parseCursor(String since) {
        try {
            return Instant.parse(since.trim());
        } catch (Exception e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "since must be an ISO-8601 instant (e.g. 2026-08-20T00:00:00Z)"
            );
        }
    }

    /**
     * Ingest replies queued on a desktop till (the "up" direction of the message
     * relay): the cloud sends each one through the shop's configured providers
     * and acknowledges per reply. Idempotent by reply id, so a retried push after
     * a partial failure never double-sends
     * (docs/scopes/DESKTOP_MESSAGES_SCOPE.md §7.3).
     */
    @PostMapping("/message-replies")
    public MessageReplyPushAck ingestMessageReplies(
            @Valid @RequestBody MessageReplyPushRequest request,
            HttpServletRequest http) {
        String businessId = TenantRequestIds.resolveBusinessId(http);
        if (request.replies() == null || request.replies().isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Batch is empty — provide at least one reply"
            );
        }
        List<MessageReplyPushAck.MessageReplyPushResult> results = new ArrayList<>();
        for (MessageReplyPushRequest.MessageReplyPushItem item : request.replies()) {
            results.add(ingestReply(businessId, item));
        }
        return new MessageReplyPushAck(results);
    }

    private MessageReplyPushAck.MessageReplyPushResult ingestReply(
            String businessId,
            MessageReplyPushRequest.MessageReplyPushItem item) {
        // Idempotent: a retried push after a network blip must not re-send a
        // reply the cloud already acknowledged — return the existing outcome.
        ContactMessageReply existing = contactMessageReplyRepository
            .findById(item.replyId())
            .orElse(null);
        if (existing != null) {
            return new MessageReplyPushAck.MessageReplyPushResult(
                existing.getId(),
                existing.getOutcome(),
                existing.getDetail(),
                existing.getCreatedAt());
        }
        try {
            ContactMessageReplyResponse resp = contactMessageService.replyTenant(
                businessId,
                item.contactMessageId(),
                new ContactMessageReplyRequest(item.channel(), item.body()),
                item.sentByUserId(),
                item.replyId());
            return new MessageReplyPushAck.MessageReplyPushResult(
                resp.id(), resp.outcome(), resp.detail(), resp.createdAt());
        } catch (ResponseStatusException e) {
            String detail = e.getReason() != null && !e.getReason().isBlank()
                ? e.getReason()
                : e.getMessage();
            return new MessageReplyPushAck.MessageReplyPushResult(item.replyId(), "failed", detail, null);
        } catch (Exception e) {
            // One bad item must never block the rest of the batch.
            log.warn(
                "[DesktopSync] message-reply ingest failed for {}: {}",
                item.replyId(), e.getMessage());
            return new MessageReplyPushAck.MessageReplyPushResult(
                item.replyId(), "failed", truncate(e.getMessage()), null);
        }
    }

    /**
     * Ingest till-uploaded supplies (Path B sessions + invoices, the "up"
     * direction of the supplies sync). Idempotent — see
     * {@link DesktopSyncIngestService}.
     */
    @PostMapping("/supplies")
    public SupplySyncAck ingestSupplies(
            @Valid @RequestBody SupplySyncSnapshot request,
            HttpServletRequest http) {
        String businessId = TenantRequestIds.resolveBusinessId(http);
        if (request.supplies() == null || request.supplies().isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Batch is empty — provide at least one supply session"
            );
        }
        return ingestService.ingestSupplies(businessId, request);
    }

    /**
     * Cloud → till supplies pull: Path B sessions posted on the cloud at/after
     * {@code since}, with lines + the resulting supplier invoice, so the till
     * can mirror web-entered supplies. Idempotent on the till side.
     */
    @GetMapping("/supplies")
    public SupplySyncSnapshot cloudSupplies(
            @RequestParam(defaultValue = "1970-01-01T00:00:00Z") String since,
            HttpServletRequest request) {
        String businessId = TenantRequestIds.resolveBusinessId(request);
        Instant cursor = parseCursor(since);
        List<RawPurchaseSession> sessions = rawPurchaseSessionRepository
            .findForDesktopSyncPull(businessId, cursor, PageRequest.of(0, 500));

        List<SupplySyncSnapshot.SupplyData> data = sessions.stream()
            .map(this::toSupplyData)
            .toList();
        return new SupplySyncSnapshot(data);
    }

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
        // posted one is the authoritative document.
        SupplierInvoice invoice = supplierInvoiceRepository
            .findByRawPurchaseSessionIdOrderByCreatedAtDesc(session.getId())
            .stream()
            .findFirst()
            .orElse(null);
        return new SupplySyncSnapshot.SupplyData(
            session.getId(),
            session.getSupplierId(),
            session.getBranchId(),
            session.getReceivedAt(),
            session.getStatus(),
            session.getNotes(),
            session.getUpdatedAt(),
            lines,
            invoice == null ? null : toInvoiceData(invoice)
        );
    }

    private SupplySyncSnapshot.InvoiceData toInvoiceData(SupplierInvoice invoice) {
        List<SupplySyncSnapshot.InvoiceLineData> lines = supplierInvoiceLineRepository
            .findByInvoiceIdOrderBySortOrderAsc(invoice.getId())
            .stream()
            .map(l -> new SupplySyncSnapshot.InvoiceLineData(
                l.getId(),
                l.getDescription(),
                l.getItemId(),
                l.getQty(),
                l.getUnitCost(),
                l.getLineTotal(),
                l.getSortOrder(),
                l.getRawLineId()))
            .toList();
        return new SupplySyncSnapshot.InvoiceData(
            invoice.getId(),
            invoice.getInvoiceNumber(),
            invoice.getInvoiceDate(),
            invoice.getDueDate(),
            invoice.getSubtotal(),
            invoice.getTaxTotal(),
            invoice.getGrandTotal(),
            invoice.getStatus(),
            invoice.getNotes(),
            lines
        );
    }

    /**
     * Ingest till-side web-order fulfillment confirmations (the "up" direction
     * of the orders sync). See {@link DesktopSyncIngestService}.
     */
    @PostMapping("/web-orders")
    public WebOrderSyncAck ingestWebOrders(
            @Valid @RequestBody WebOrderSyncSnapshot request,
            HttpServletRequest http) {
        String businessId = TenantRequestIds.resolveBusinessId(http);
        if (request.orders() == null || request.orders().isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Batch is empty — provide at least one web order"
            );
        }
        return ingestService.ingestWebOrders(businessId, request);
    }

    /**
     * Cloud → till web-orders pull: every order touched at/after {@code since}
     * (status + fulfillment status + lines), so the cashier sees online-shop
     * orders — including "paid, awaiting confirmation" — right at the till.
     * Idempotent on the till side (it upserts by order id).
     */
    @GetMapping("/web-orders")
    public WebOrderSyncSnapshot cloudWebOrders(
            @RequestParam(defaultValue = "1970-01-01T00:00:00Z") String since,
            HttpServletRequest request) {
        String businessId = TenantRequestIds.resolveBusinessId(request);
        Instant cursor = parseCursor(since);
        List<WebOrder> orders = webOrderRepository
            .findForDesktopSyncPull(businessId, cursor, PageRequest.of(0, 500));

        List<WebOrderSyncSnapshot.OrderData> data = orders.stream()
            .map(this::toWebOrderData)
            .toList();
        return new WebOrderSyncSnapshot(data);
    }

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

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
