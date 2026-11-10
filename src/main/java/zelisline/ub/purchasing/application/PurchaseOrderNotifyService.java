package zelisline.ub.purchasing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.messaging.application.CustomerMessageDispatcher;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.messaging.domain.SmsSendReason;
import zelisline.ub.opsalerts.application.BusinessOpsAlertSettingsService;
import zelisline.ub.opsalerts.application.TenantOpsAlertDispatcher;
import zelisline.ub.opsalerts.domain.BusinessOpsAlertSettings;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.api.dto.NotifyPurchaseOrderRequest;
import zelisline.ub.purchasing.api.dto.NotifyPurchaseOrderResponse;
import zelisline.ub.purchasing.api.dto.NotifyPurchaseOrderResponse.Delivery;
import zelisline.ub.purchasing.domain.PurchaseOrder;
import zelisline.ub.purchasing.domain.PurchaseOrderLine;
import zelisline.ub.purchasing.repository.PurchaseOrderLineRepository;
import zelisline.ub.purchasing.repository.PurchaseOrderRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierContact;
import zelisline.ub.suppliers.repository.SupplierContactRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Optional WhatsApp / SMS fan-out of a saved purchase order to the admin and the supplier.
 *
 * <p>Deliberately not transactional: it makes outbound HTTP calls and must never hold a
 * database transaction open for them. A delivery problem is reported per recipient in the
 * response and never turns into an HTTP error, because the order itself is already saved.
 */
@Service
@RequiredArgsConstructor
public class PurchaseOrderNotifyService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseOrderNotifyService.class);

    public static final String CHANNEL_WHATSAPP = "whatsapp";
    public static final String CHANNEL_SMS = "sms";
    public static final String CHANNEL_BOTH = "both";

    /** SMS is billed per segment, so the SMS body lists only the first few lines. */
    static final int MAX_SMS_ITEMS = 6;
    /** WhatsApp text limit is 4096; stay well under it. */
    static final int MAX_WHATSAPP_CHARS = 3500;

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderLineRepository purchaseOrderLineRepository;
    private final ItemRepository itemRepository;
    private final SupplierRepository supplierRepository;
    private final SupplierContactRepository supplierContactRepository;
    private final BranchRepository branchRepository;
    private final BusinessRepository businessRepository;
    private final BusinessOpsAlertSettingsService opsAlertSettingsService;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final CustomerMessageDispatcher messageDispatcher;

    public NotifyPurchaseOrderResponse notifyParties(
            String businessId,
            String purchaseOrderId,
            NotifyPurchaseOrderRequest req
    ) {
        String adminChannel = req.notifyAdmin() ? normalizeChannel(req.adminChannel()) : null;
        String supplierChannel = req.notifySupplier() ? normalizeChannel(req.supplierChannel()) : null;

        PurchaseOrder po = purchaseOrderRepository.findByIdAndBusinessId(purchaseOrderId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Purchase order not found"));
        if (PurchasingConstants.PO_CANCELLED.equals(po.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Purchase order is cancelled");
        }
        List<PurchaseOrderLine> lines =
                purchaseOrderLineRepository.findByPurchaseOrderIdOrderBySortOrderAscIdAsc(po.getId());
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Purchase order has no lines");
        }
        if (adminChannel == null && supplierChannel == null) {
            return new NotifyPurchaseOrderResponse(List.of());
        }

        Supplier supplier = supplierRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(po.getSupplierId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
        Business business = businessRepository.findById(businessId).orElse(null);
        String shop = business != null && notBlank(business.getName()) ? business.getName().trim() : "our shop";
        String currency = business != null && notBlank(business.getCurrency()) ? business.getCurrency().trim() : "KES";
        String branchName = branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(po.getBranchId(), businessId)
                .map(Branch::getName)
                .filter(PurchaseOrderNotifyService::notBlank)
                .orElse(null);

        List<OrderLine> orderLines = toOrderLines(businessId, lines);
        OrderSummary order = new OrderSummary(
                shop, branchName, supplier.getName(), po.getPoNumber(), po.getExpectedDate(), currency, orderLines);

        List<Delivery> out = new ArrayList<>();
        if (adminChannel != null) {
            out.add(notifyAdmin(businessId, adminChannel, order));
        }
        if (supplierChannel != null) {
            out.add(notifySupplier(businessId, supplier, supplierChannel, order));
        }
        return new NotifyPurchaseOrderResponse(out);
    }

    // ---- recipients ---------------------------------------------------------------------------

    private Delivery notifyAdmin(String businessId, String channel, OrderSummary order) {
        try {
            BusinessOpsAlertSettings settings = opsAlertSettingsService.resolveForBusiness(businessId);
            if (!settings.hasVerifiedPhone()) {
                return skipped("admin", channel, null,
                        "No verified admin number. Add one under Business → WhatsApp alerts.");
            }
            String phone = toMsisdn(settings.getPhone());
            if (phone == null) {
                return skipped("admin", channel, null, "The admin number on file is not valid.");
            }
            TenantMessagingConfig messaging =
                    messagingSettingsService.resolveForTest(businessId, SmsSendReason.OPS_ALERT);
            return send("admin", channel, phone, messaging,
                    buildAdminWhatsApp(order), buildAdminSms(order));
        } catch (RuntimeException ex) {
            log.warn("po_notify admin failed business={}", businessId, ex);
            return failed("admin", channel, null, "Could not send to the admin.");
        }
    }

    private Delivery notifySupplier(String businessId, Supplier supplier, String channel, OrderSummary order) {
        try {
            String phone = toMsisdn(resolveSupplierPhone(supplier));
            if (phone == null) {
                return skipped("supplier", channel, null,
                        "No phone number on file for " + supplier.getName() + ".");
            }
            TenantMessagingConfig messaging =
                    messagingSettingsService.resolveForTest(businessId, SmsSendReason.NOTIFICATION);
            return send("supplier", channel, phone, messaging,
                    buildSupplierWhatsApp(order), buildSupplierSms(order));
        } catch (RuntimeException ex) {
            log.warn("po_notify supplier failed business={} supplier={}", businessId, supplier.getId(), ex);
            return failed("supplier", channel, null, "Could not send to the supplier.");
        }
    }

    /** Primary contact first, then any contact with a phone, then the payout phone. */
    private String resolveSupplierPhone(Supplier supplier) {
        for (SupplierContact c : supplierContactRepository
                .findBySupplierIdOrderByPrimaryContactDescNameAsc(supplier.getId())) {
            if (notBlank(c.getPhone())) {
                return c.getPhone();
            }
        }
        return supplier.getPayoutPhone();
    }

    // ---- delivery -----------------------------------------------------------------------------

    private Delivery send(
            String recipient,
            String channel,
            String phone,
            TenantMessagingConfig messaging,
            String whatsAppText,
            String smsText
    ) {
        String masked = maskPhone(phone);
        if (!messaging.secretsReadable()) {
            log.warn("po_notify {} messaging secrets unreadable", recipient);
            return skipped(recipient, channel, masked, "Messaging credentials could not be read.");
        }
        boolean wantWa = CHANNEL_WHATSAPP.equals(channel) || CHANNEL_BOTH.equals(channel);
        boolean wantSms = CHANNEL_SMS.equals(channel) || CHANNEL_BOTH.equals(channel);
        boolean waReady = messaging.metaWhatsAppConfigured();
        boolean smsReady = messaging.smsConfigured();

        List<String> notes = new ArrayList<>();
        boolean anySent = false;
        boolean anyAttempted = false;

        if (wantWa) {
            if (!waReady) {
                notes.add("WhatsApp is not set up");
            } else {
                anyAttempted = true;
                var r = messageDispatcher.deliverWhatsAppOnly(messaging, phone, whatsAppText);
                log.info("po_notify {} phone={} channel=whatsapp outcome={} detail={}",
                        recipient, masked, r.outcome(), r.detail());
                if ("sent".equals(r.outcome())) {
                    anySent = true;
                    notes.add("WhatsApp sent");
                } else {
                    notes.add("WhatsApp failed");
                }
            }
        }
        if (wantSms) {
            if (!smsReady) {
                notes.add("SMS is not set up");
            } else {
                anyAttempted = true;
                var r = messageDispatcher.deliverSmsOnly(messaging, phone, smsText);
                log.info("po_notify {} phone={} channel=sms outcome={} detail={}",
                        recipient, masked, r.outcome(), r.detail());
                if ("sent".equals(r.outcome()) || "stub".equals(r.outcome())) {
                    anySent = true;
                    notes.add("SMS sent");
                } else {
                    notes.add("SMS failed");
                }
            }
        }
        String detail = String.join("; ", notes) + ".";
        if (anySent) {
            return new Delivery(recipient, channel, "sent", detail, masked);
        }
        return anyAttempted
                ? failed(recipient, channel, masked, detail)
                : skipped(recipient, channel, masked, detail);
    }

    private static Delivery skipped(String recipient, String channel, String masked, String detail) {
        return new Delivery(recipient, channel, "skipped", detail, masked);
    }

    private static Delivery failed(String recipient, String channel, String masked, String detail) {
        return new Delivery(recipient, channel, "failed", detail, masked);
    }

    // ---- message bodies -----------------------------------------------------------------------

    static String buildAdminWhatsApp(OrderSummary o) {
        StringBuilder sb = new StringBuilder();
        sb.append(o.shop()).append(" — purchase order ").append(o.poNumber()).append('\n');
        sb.append("Supplier: ").append(o.supplierName()).append('\n');
        if (o.branchName() != null) {
            sb.append("Branch: ").append(o.branchName()).append('\n');
        }
        if (o.expectedDate() != null) {
            sb.append("Expected: ").append(o.expectedDate()).append('\n');
        }
        appendFullList(sb, o);
        return sb.toString().trim();
    }

    static String buildSupplierWhatsApp(OrderSummary o) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hello ").append(o.supplierName()).append(",\n");
        sb.append("New order ").append(o.poNumber()).append(" from ").append(o.shop());
        if (o.branchName() != null) {
            sb.append(" (").append(o.branchName()).append(')');
        }
        sb.append(":\n");
        if (o.expectedDate() != null) {
            sb.append("Needed by: ").append(o.expectedDate()).append('\n');
        }
        appendFullList(sb, o);
        sb.append("\n\nPlease confirm availability. Thank you.");
        return sb.toString().trim();
    }

    static String buildAdminSms(OrderSummary o) {
        return o.shop() + " PO " + o.poNumber() + " to " + o.supplierName() + ": "
                + compactItems(o) + " Total " + money(o.total(), o.currency()) + ".";
    }

    static String buildSupplierSms(OrderSummary o) {
        return o.shop() + " order " + o.poNumber() + ": " + compactItems(o)
                + " Total " + money(o.total(), o.currency()) + ". Please confirm.";
    }

    private static void appendFullList(StringBuilder sb, OrderSummary o) {
        List<OrderLine> lines = o.lines();
        sb.append("Items (").append(lines.size()).append("):");
        int shown = 0;
        for (OrderLine l : lines) {
            String row = "\n" + (shown + 1) + ". " + l.name() + " — " + qty(l.qty()) + lineMoney(l, o.currency());
            // Keep room for the footer so the total is never the part that gets cut.
            if (sb.length() + row.length() > MAX_WHATSAPP_CHARS - 120) {
                break;
            }
            sb.append(row);
            shown++;
        }
        if (shown < lines.size()) {
            sb.append("\n…and ").append(lines.size() - shown).append(" more");
        }
        sb.append("\nTotal: ").append(money(o.total(), o.currency()));
    }

    private static String compactItems(OrderSummary o) {
        List<OrderLine> lines = o.lines();
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(MAX_SMS_ITEMS, lines.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(lines.get(i).name()).append(" x").append(qty(lines.get(i).qty()));
        }
        if (lines.size() > shown) {
            sb.append(" +").append(lines.size() - shown).append(" more");
        }
        sb.append('.');
        return sb.toString();
    }

    private static String lineMoney(OrderLine l, String currency) {
        if (l.unitCost() == null) {
            return "";
        }
        return " × " + money(l.unitCost(), currency) + " = " + money(l.total(), currency);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private List<OrderLine> toOrderLines(String businessId, List<PurchaseOrderLine> lines) {
        List<String> ids = lines.stream().map(PurchaseOrderLine::getItemId).distinct().toList();
        Map<String, Item> items = itemRepository.findAllById(ids).stream()
                .filter(i -> businessId.equals(i.getBusinessId()))
                .collect(Collectors.toMap(Item::getId, Function.identity(), (a, b) -> a));
        return lines.stream()
                .map(l -> {
                    Item item = items.get(l.getItemId());
                    String name = item != null && notBlank(item.getName()) ? item.getName().trim() : "Item";
                    return new OrderLine(name, l.getQtyOrdered(), l.getUnitEstimatedCost());
                })
                .toList();
    }

    static String normalizeChannel(String raw) {
        if (raw == null || raw.isBlank()) {
            return CHANNEL_WHATSAPP;
        }
        String c = raw.trim().toLowerCase(Locale.ROOT);
        if (CHANNEL_WHATSAPP.equals(c) || CHANNEL_SMS.equals(c) || CHANNEL_BOTH.equals(c)) {
            return c;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Channel must be whatsapp, sms or both");
    }

    /** {@code 2547…} digits for providers; {@code null} when the number is unusable. */
    static String toMsisdn(String raw) {
        if (!notBlank(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        String digits = trimmed.replaceAll("\\D", "");
        // An explicit non-Kenyan "+<country code>" number must not get a 254 prefix bolted on.
        if (trimmed.startsWith("+") && !digits.startsWith("254") && digits.length() >= 9) {
            return digits;
        }
        String msisdn = StkPhoneNormalizer.normalize(trimmed);
        if (msisdn != null) {
            return msisdn;
        }
        return digits.length() >= 9 ? digits : null;
    }

    static String maskPhone(String phone) {
        if (phone == null || phone.length() < 6) {
            return "***";
        }
        return phone.substring(0, 5) + "****" + phone.substring(phone.length() - 2);
    }

    private static String qty(BigDecimal q) {
        if (q == null) {
            return "0";
        }
        BigDecimal s = q.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }

    private static String money(BigDecimal amount, String currency) {
        return TenantOpsAlertDispatcher.formatMoney(amount, currency);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    // ---- value types --------------------------------------------------------------------------

    record OrderLine(String name, BigDecimal qty, BigDecimal unitCost) {
        BigDecimal total() {
            if (qty == null || unitCost == null) {
                return BigDecimal.ZERO;
            }
            return qty.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
        }
    }

    record OrderSummary(
            String shop,
            String branchName,
            String supplierName,
            String poNumber,
            LocalDate expectedDate,
            String currency,
            List<OrderLine> lines
    ) {
        BigDecimal total() {
            return lines.stream().map(OrderLine::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }
}
