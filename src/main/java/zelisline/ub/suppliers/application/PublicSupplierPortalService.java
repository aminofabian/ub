package zelisline.ub.suppliers.application;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.pageseal.application.PageSealService;
import zelisline.ub.catalog.application.ProductDisplayName;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.messages.domain.ContactMessage;
import zelisline.ub.messages.domain.ContactMessageScope;
import zelisline.ub.messages.domain.ContactMessageStatus;
import zelisline.ub.messages.repository.ContactMessageRepository;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.domain.SupplierInvoice;
import zelisline.ub.purchasing.domain.SupplierInvoiceLine;
import zelisline.ub.purchasing.repository.SupplierInvoiceLineRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceRepository;
import zelisline.ub.suppliers.api.dto.PublicSupplierComplaintRequest;
import zelisline.ub.suppliers.api.dto.PublicSupplierComplaintResponse;
import zelisline.ub.suppliers.api.dto.PublicSupplierMovementRow;
import zelisline.ub.suppliers.api.dto.PublicSupplierPortalResponse;
import zelisline.ub.suppliers.api.dto.PublicSupplierProductsSellingResponse;
import zelisline.ub.suppliers.api.dto.PublicSupplierProductsSellingResponse.PublicSupplierProductSellingRow;
import zelisline.ub.suppliers.api.dto.PublicSupplierSupplyLine;
import zelisline.ub.suppliers.api.dto.PublicSupplierSupplyRow;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.domain.SupplierSlug;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class PublicSupplierPortalService {

    private static final int SUPPLY_LIMIT = 40;
    private static final int MOVEMENT_LIMIT = 50;
    private static final int PRODUCT_LIMIT = 60;
    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    private final SupplierRepository supplierRepository;
    private final SupplierPurchaseHistoryService purchaseHistoryService;
    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final SupplierInvoiceLineRepository supplierInvoiceLineRepository;
    private final SupplierProductRepository supplierProductRepository;
    private final ItemRepository itemRepository;
    private final BusinessRepository businessRepository;
    private final ContactMessageRepository contactMessageRepository;
    private final zelisline.ub.marketplace.application.SupplierPortalMessagesService messagesService;
    private final PageSealService pageSealService;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public PublicSupplierPortalResponse overview(String businessId, String slugRaw, String unlockToken) {
        Supplier supplier = resolveSupplierOrThrow(businessId, slugRaw);
        Business business = businessRepository.findById(businessId).orElse(null);
        String shopName = business != null && business.getName() != null
                ? business.getName().trim()
                : "Shop";
        String currency = business != null && business.getCurrency() != null
                ? business.getCurrency().trim()
                : "KES";
        String slug = SupplierSlug.canonical(supplier.getName(), supplier.getCode());

        BigDecimal advanceCredit = supplier.getPrepaymentBalance() != null
                ? supplier.getPrepaymentBalance()
                : BigDecimal.ZERO;

        if (supplier.isPageSealed() && !pageSealService.isShopSupplierUnlocked(supplier, unlockToken)) {
            return new PublicSupplierPortalResponse(
                    supplier.getName(),
                    slug,
                    shopName,
                    currency,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    0,
                    List.of(),
                    List.of(),
                    List.of());
        }

        var history = purchaseHistoryService.purchaseHistory(businessId, supplier.getId(), SUPPLY_LIMIT);
        List<PublicSupplierSupplyRow> supplies = history.orders().stream()
                .map(row -> new PublicSupplierSupplyRow(
                        row.invoiceNumber(),
                        row.invoiceDate(),
                        row.grandTotal(),
                        row.amountPaid(),
                        row.balanceOpen(),
                        row.paymentStatus(),
                        row.sourceType(),
                        supplyLines(row.supplierInvoiceId())))
                .toList();

        List<PublicSupplierMovementRow> movements = recentMovements(businessId, supplier.getId());
        List<String> linked = linkedProductNames(supplier.getId(), businessId);

        return new PublicSupplierPortalResponse(
                supplier.getName(),
                slug,
                shopName,
                currency,
                history.summary().openBalance(),
                history.summary().totalSpent(),
                history.summary().totalPaid(),
                advanceCredit,
                history.summary().invoiceCount(),
                supplies,
                movements,
                linked);
    }

    @Transactional(readOnly = true)
    public PublicSupplierProductsSellingResponse productsSelling(
            String businessId,
            String slugRaw,
            String periodRaw,
            String sortRaw,
            String unlockToken
    ) {
        Supplier supplier = resolveSupplierOrThrow(businessId, slugRaw);
        Business business = businessRepository.findById(businessId).orElse(null);
        String currency = business != null && business.getCurrency() != null
                ? business.getCurrency().trim()
                : "KES";
        String period = normalizePeriod(periodRaw);
        String sort = normalizeSort(sortRaw);
        LocalDate today = LocalDate.now(NAIROBI);
        LocalDate periodStart = periodStart(today, period);
        LocalDate periodEnd = today;

        if (supplier.isPageSealed() && !pageSealService.isShopSupplierUnlocked(supplier, unlockToken)) {
            return new PublicSupplierProductsSellingResponse(
                    period, periodStart, periodEnd, currency, sort, List.of());
        }

        Instant since = periodStart.atStartOfDay(NAIROBI).toInstant();
        List<PublicSupplierProductSellingRow> products = jdbc.query(
                """
                        SELECT i.id AS item_id,
                               COALESCE(NULLIF(TRIM(i.name), ''), 'Product') AS product_name,
                               NULLIF(TRIM(i.variant_name), '') AS variant_name,
                               i.sku AS sku,
                               COALESCE(i.current_stock, 0) AS current_stock,
                               COALESCE(sold.units_sold, 0) AS units_sold,
                               COALESCE(sold.revenue, 0) AS revenue,
                               sold.last_sold_at AS last_sold_at
                          FROM supplier_products sp
                          JOIN items i ON i.id = sp.item_id
                           AND i.business_id = ?
                           AND i.deleted_at IS NULL
                     LEFT JOIN (
                                SELECT si.item_id AS item_id,
                                       SUM(si.quantity) AS units_sold,
                                       SUM(si.line_total) AS revenue,
                                       MAX(s.sold_at) AS last_sold_at
                                  FROM sale_items si
                                  JOIN sales s ON s.id = si.sale_id
                                 WHERE s.business_id = ?
                                   AND s.status = 'completed'
                                   AND s.voided_at IS NULL
                                   AND s.sold_at >= ?
                              GROUP BY si.item_id
                              ) sold ON sold.item_id = i.id
                         WHERE sp.supplier_id = ?
                           AND sp.active = TRUE
                           AND sp.deleted_at IS NULL
                        """,
                (rs, rowNum) -> {
                    String name = rs.getString("product_name");
                    String variant = rs.getString("variant_name");
                    if (variant != null && !variant.isBlank()) {
                        name = ProductDisplayName.join(name, variant);
                    }
                    Timestamp lastTs = rs.getTimestamp("last_sold_at");
                    return new PublicSupplierProductSellingRow(
                            rs.getString("item_id"),
                            name,
                            rs.getString("sku"),
                            nullSafe(rs.getBigDecimal("units_sold")),
                            nullSafe(rs.getBigDecimal("revenue")),
                            nullSafe(rs.getBigDecimal("current_stock")),
                            lastTs != null ? lastTs.toInstant() : null);
                },
                businessId,
                businessId,
                Timestamp.from(since),
                supplier.getId());

        Comparator<PublicSupplierProductSellingRow> bySpeed = "revenue".equals(sort)
                ? Comparator.comparing(
                        PublicSupplierProductSellingRow::revenue,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                : Comparator.comparing(
                        PublicSupplierProductSellingRow::unitsSold,
                        Comparator.nullsLast(Comparator.reverseOrder()));
        products = products.stream()
                .sorted(bySpeed.thenComparing(
                        PublicSupplierProductSellingRow::name,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .limit(PRODUCT_LIMIT)
                .toList();

        return new PublicSupplierProductsSellingResponse(
                period, periodStart, periodEnd, currency, sort, products);
    }

    private static String normalizePeriod(String raw) {
        if (raw == null || raw.isBlank()) {
            return "week";
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "day", "today", "1d" -> "today";
            case "month", "30d", "thismonth" -> "month";
            default -> "week";
        };
    }

    private static String normalizeSort(String raw) {
        if (raw == null || raw.isBlank()) {
            return "units";
        }
        return "revenue".equalsIgnoreCase(raw.trim()) ? "revenue" : "units";
    }

    private static LocalDate periodStart(LocalDate today, String period) {
        return switch (period) {
            case "today" -> today;
            case "month" -> today.minusDays(29);
            default -> today.minusDays(6);
        };
    }

    private static BigDecimal nullSafe(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private List<PublicSupplierSupplyLine> supplyLines(String invoiceId) {
        if (invoiceId == null || invoiceId.isBlank()) {
            return List.of();
        }
        return supplierInvoiceLineRepository.findByInvoiceIdOrderBySortOrderAsc(invoiceId).stream()
                .map(line -> new PublicSupplierSupplyLine(
                        line.getDescription(),
                        line.getQty(),
                        line.getUnitCost(),
                        line.getLineTotal()))
                .toList();
    }

    @Transactional
    public PublicSupplierComplaintResponse submitComplaint(
            String businessId,
            String slugRaw,
            @Valid PublicSupplierComplaintRequest body,
            HttpServletRequest request
    ) {
        if (body.website() != null && !body.website().isBlank()) {
            return new PublicSupplierComplaintResponse(true, "ok");
        }
        String message = body.message() != null ? body.message().trim() : "";
        if (message.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please write a short note");
        }
        Supplier supplier = resolveSupplierOrThrow(businessId, slugRaw);
        String slug = SupplierSlug.canonical(supplier.getName(), supplier.getCode());
        String fromName = body.name() != null && !body.name().isBlank()
                ? body.name().trim()
                : supplier.getName();

        ContactMessage row = new ContactMessage();
        row.setScope(ContactMessageScope.TENANT);
        row.setBusinessId(businessId);
        row.setName(trimTo(fromName, 120));
        row.setEmail("supplier-portal@" + businessId.replace("-", "").substring(0, 8) + ".local");
        if (body.phone() != null && !body.phone().isBlank()) {
            row.setPhone(trimTo(body.phone().trim(), 32));
        }
        row.setBody(trimTo(
                "[Supplier portal · /s/" + slug + "]\n" + message,
                4000));
        row.setStatus(ContactMessageStatus.UNREAD);
        row.setSourcePath("/s/" + slug);
        String ua = request.getHeader("User-Agent");
        if (ua != null && !ua.isBlank()) {
            row.setUserAgent(ua.length() > 512 ? ua.substring(0, 512) : ua);
        }
        ContactMessage saved = contactMessageRepository.save(row);
        if (supplier.getMarketplaceSupplierId() != null && !supplier.getMarketplaceSupplierId().isBlank()) {
            messagesService.recordFromShop(
                    supplier.getMarketplaceSupplierId(),
                    businessId,
                    supplier.getId(),
                    fromName,
                    message,
                    saved.getId());
        }
        return new PublicSupplierComplaintResponse(true, saved.getId());
    }

    public Supplier resolveSupplierOrThrow(String businessId, String slugRaw) {
        String needle = slugRaw == null ? "" : slugRaw.trim();
        if (needle.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found");
        }

        List<Supplier> pool = supplierRepository.findAllByBusinessIdNotDeleted(businessId);

        List<Supplier> exact = pool.stream()
                .filter(s -> SupplierSlug.matches(s.getId(), s.getName(), s.getCode(), needle))
                .toList();
        if (exact.size() == 1) {
            return exact.getFirst();
        }

        List<Supplier> loose = pool.stream()
                .filter(s -> SupplierSlug.matchesLoose(s.getId(), s.getName(), s.getCode(), needle))
                .toList();
        if (loose.size() == 1) {
            return loose.getFirst();
        }

        String hint = SupplierSlug.searchHint(needle);
        if (!hint.isBlank()) {
            List<Supplier> searched = supplierRepository.searchByNameOrCode(businessId, hint);
            if (searched.size() == 1) {
                return searched.getFirst();
            }
            // Prefer a unique loose match within search hits (narrower than full pool).
            List<Supplier> searchedLoose = searched.stream()
                    .filter(s -> SupplierSlug.matchesLoose(s.getId(), s.getName(), s.getCode(), needle))
                    .toList();
            if (searchedLoose.size() == 1) {
                return searchedLoose.getFirst();
            }
        }

        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found");
    }

    private List<PublicSupplierMovementRow> recentMovements(String businessId, String supplierId) {
        List<SupplierInvoice> invs = supplierInvoiceRepository
                .findByBusinessIdAndSupplierIdAndStatus(
                        businessId, supplierId, PurchasingConstants.INVOICE_POSTED);
        invs.sort(Comparator
                .comparing(SupplierInvoice::getInvoiceDate, Comparator.reverseOrder())
                .thenComparing(SupplierInvoice::getCreatedAt, Comparator.reverseOrder()));

        List<PublicSupplierMovementRow> out = new ArrayList<>(MOVEMENT_LIMIT);
        for (SupplierInvoice inv : invs) {
            if (out.size() >= MOVEMENT_LIMIT) {
                break;
            }
            List<SupplierInvoiceLine> lines =
                    supplierInvoiceLineRepository.findByInvoiceIdOrderBySortOrderAsc(inv.getId());
            for (SupplierInvoiceLine line : lines) {
                if (out.size() >= MOVEMENT_LIMIT) {
                    break;
                }
                out.add(new PublicSupplierMovementRow(
                        line.getDescription(),
                        line.getQty(),
                        line.getUnitCost(),
                        line.getLineTotal(),
                        inv.getInvoiceDate(),
                        inv.getInvoiceNumber()));
            }
        }
        return List.copyOf(out);
    }

    private List<String> linkedProductNames(String supplierId, String businessId) {
        List<SupplierProduct> links = supplierProductRepository.listActivePublicForSupplier(supplierId);
        if (links.isEmpty()) {
            return List.of();
        }
        List<String> itemIds = links.stream()
                .map(SupplierProduct::getItemId)
                .distinct()
                .limit(PRODUCT_LIMIT)
                .toList();
        Map<String, Item> byId = itemRepository
                .findByIdInAndBusinessIdAndDeletedAtIsNull(itemIds, businessId)
                .stream()
                .collect(Collectors.toMap(Item::getId, i -> i, (a, b) -> a, HashMap::new));
        List<String> names = new ArrayList<>();
        for (String itemId : itemIds) {
            Item item = byId.get(itemId);
            if (item == null) {
                continue;
            }
            String label = item.getName() != null ? item.getName().trim() : "";
            if (item.getVariantName() != null && !item.getVariantName().isBlank()) {
                label = ProductDisplayName.join(label, item.getVariantName());
            }
            if (!label.isBlank()) {
                names.add(label);
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(names);
    }

    private static String trimTo(String value, int max) {
        String t = value == null ? "" : value.trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, max);
    }
}
