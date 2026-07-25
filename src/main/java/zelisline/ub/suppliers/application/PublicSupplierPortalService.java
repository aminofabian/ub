package zelisline.ub.suppliers.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

    private final SupplierRepository supplierRepository;
    private final SupplierPurchaseHistoryService purchaseHistoryService;
    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final SupplierInvoiceLineRepository supplierInvoiceLineRepository;
    private final SupplierProductRepository supplierProductRepository;
    private final ItemRepository itemRepository;
    private final BusinessRepository businessRepository;
    private final ContactMessageRepository contactMessageRepository;

    @Transactional(readOnly = true)
    public PublicSupplierPortalResponse overview(String businessId, String slugRaw) {
        Supplier supplier = resolveSupplierOrThrow(businessId, slugRaw);
        Business business = businessRepository.findById(businessId).orElse(null);
        String shopName = business != null && business.getName() != null
                ? business.getName().trim()
                : "Shop";
        String currency = business != null && business.getCurrency() != null
                ? business.getCurrency().trim()
                : "KES";

        var history = purchaseHistoryService.purchaseHistory(businessId, supplier.getId(), SUPPLY_LIMIT);
        List<PublicSupplierSupplyRow> supplies = history.orders().stream()
                .map(row -> new PublicSupplierSupplyRow(
                        row.invoiceNumber(),
                        row.invoiceDate(),
                        row.grandTotal(),
                        row.amountPaid(),
                        row.balanceOpen(),
                        row.paymentStatus(),
                        row.sourceType()))
                .toList();

        List<PublicSupplierMovementRow> movements = recentMovements(businessId, supplier.getId());
        List<String> linked = linkedProductNames(supplier.getId(), businessId);
        String slug = SupplierSlug.canonical(supplier.getName(), supplier.getCode());

        return new PublicSupplierPortalResponse(
                supplier.getName(),
                slug,
                shopName,
                currency,
                history.summary().openBalance(),
                history.summary().totalSpent(),
                history.summary().totalPaid(),
                history.summary().invoiceCount(),
                supplies,
                movements,
                linked);
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
        return new PublicSupplierComplaintResponse(true, saved.getId());
    }

    Supplier resolveSupplierOrThrow(String businessId, String slugRaw) {
        String needle = slugRaw == null ? "" : slugRaw.trim();
        if (needle.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found");
        }
        List<Supplier> active = supplierRepository.findActiveByBusinessId(businessId);
        List<Supplier> matches = active.stream()
                .filter(s -> SupplierSlug.matches(s.getId(), s.getName(), s.getCode(), needle))
                .toList();
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        if (matches.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found");
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
                label = label + " · " + item.getVariantName().trim();
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
