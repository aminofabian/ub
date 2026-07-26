package zelisline.ub.marketplace.application;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.SupplierPortalHubShopDetailResponse;
import zelisline.ub.marketplace.api.dto.SupplierPortalShopProductRow;
import zelisline.ub.marketplace.domain.BusinessSupplierConnection;
import zelisline.ub.marketplace.domain.BusinessSupplierConnectionStatuses;
import zelisline.ub.marketplace.repository.BusinessSupplierConnectionRepository;
import zelisline.ub.messages.domain.ContactMessage;
import zelisline.ub.messages.domain.ContactMessageScope;
import zelisline.ub.messages.domain.ContactMessageStatus;
import zelisline.ub.messages.repository.ContactMessageRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceLineRepository;
import zelisline.ub.suppliers.api.dto.PublicSupplierComplaintRequest;
import zelisline.ub.suppliers.api.dto.PublicSupplierComplaintResponse;
import zelisline.ub.suppliers.api.dto.PublicSupplierSupplyLine;
import zelisline.ub.suppliers.api.dto.PublicSupplierSupplyRow;
import zelisline.ub.suppliers.api.dto.SupplierItemLinkResponse;
import zelisline.ub.suppliers.application.ItemSupplierLinkService;
import zelisline.ub.suppliers.application.SupplierPurchaseHistoryService;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierSlug;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.application.BranchResolutionService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class SupplierPortalHubService {

    private static final int SUPPLY_LIMIT = 40;

    private final BusinessSupplierConnectionRepository connectionRepository;
    private final SupplierRepository supplierRepository;
    private final BusinessRepository businessRepository;
    private final SupplierPurchaseHistoryService purchaseHistoryService;
    private final SupplierInvoiceLineRepository supplierInvoiceLineRepository;
    private final ContactMessageRepository contactMessageRepository;
    private final SupplierPortalMessagesService messagesService;
    private final ItemSupplierLinkService itemSupplierLinkService;
    private final BranchResolutionService branchResolutionService;

    @Transactional(readOnly = true)
    public List<SupplierPortalShopProductRow> shopProducts(
            String marketplaceSupplierId,
            String localSupplierId
    ) {
        BusinessSupplierConnection link = requireActiveLink(marketplaceSupplierId, localSupplierId);
        String branchId = branchResolutionService.resolveDefaultBranch(link.getBusinessId());
        List<SupplierItemLinkResponse> links = itemSupplierLinkService.listLinksForSupplier(
                link.getBusinessId(), localSupplierId, branchId);
        return links.stream()
                .filter(SupplierItemLinkResponse::active)
                .map(row -> new SupplierPortalShopProductRow(
                        row.itemId(),
                        row.itemName(),
                        row.sku(),
                        row.barcode(),
                        row.thumbnailUrl(),
                        row.currentStock(),
                        row.defaultCostPrice(),
                        row.lastCostPrice(),
                        row.packSize(),
                        row.packUnit(),
                        row.variantName(),
                        row.parentItemName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public SupplierPortalHubShopDetailResponse shopSupplies(
            String marketplaceSupplierId,
            String localSupplierId
    ) {
        BusinessSupplierConnection link = requireActiveLink(marketplaceSupplierId, localSupplierId);
        Supplier local = supplierRepository.findByIdAndDeletedAtIsNull(localSupplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
        Business business = businessRepository.findById(link.getBusinessId()).orElse(null);
        String shopName = business != null && business.getName() != null
                ? business.getName().trim()
                : "Shop";
        String currency = business != null && business.getCurrency() != null
                ? business.getCurrency().trim()
                : "KES";

        var history = purchaseHistoryService.purchaseHistory(
                link.getBusinessId(), local.getId(), SUPPLY_LIMIT);
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

        return new SupplierPortalHubShopDetailResponse(
                link.getBusinessId(),
                shopName,
                local.getId(),
                local.getName(),
                currency,
                history.summary(),
                supplies);
    }

    @Transactional
    public PublicSupplierComplaintResponse submitShopComplaint(
            String marketplaceSupplierId,
            String localSupplierId,
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
        BusinessSupplierConnection link = requireActiveLink(marketplaceSupplierId, localSupplierId);
        Supplier local = supplierRepository.findByIdAndDeletedAtIsNull(localSupplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
        String slug = SupplierSlug.canonical(local.getName(), local.getCode());
        String fromName = body.name() != null && !body.name().isBlank()
                ? body.name().trim()
                : local.getName();

        ContactMessage row = new ContactMessage();
        row.setScope(ContactMessageScope.TENANT);
        row.setBusinessId(link.getBusinessId());
        row.setName(trimTo(fromName, 120));
        row.setEmail("supplier-hub@" + marketplaceSupplierId.replace("-", "").substring(0, 8) + ".local");
        if (body.phone() != null && !body.phone().isBlank()) {
            row.setPhone(trimTo(body.phone().trim(), 32));
        }
        row.setBody(trimTo(
                "[Global supplier hub · /s/" + slug + "]\n" + message,
                4000));
        row.setStatus(ContactMessageStatus.UNREAD);
        row.setSourcePath("/s/" + slug);
        String ua = request.getHeader("User-Agent");
        if (ua != null && !ua.isBlank()) {
            row.setUserAgent(ua.length() > 512 ? ua.substring(0, 512) : ua);
        }
        ContactMessage saved = contactMessageRepository.save(row);
        messagesService.recordFromShop(
                marketplaceSupplierId,
                link.getBusinessId(),
                localSupplierId,
                fromName,
                message,
                saved.getId());
        return new PublicSupplierComplaintResponse(true, saved.getId());
    }

    private BusinessSupplierConnection requireActiveLink(String marketplaceSupplierId, String localSupplierId) {
        BusinessSupplierConnection link = connectionRepository
                .findByMarketplaceSupplierIdAndLocalSupplierId(marketplaceSupplierId, localSupplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop link not found"));
        if (!BusinessSupplierConnectionStatuses.ACTIVE.equals(link.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop link not found");
        }
        return link;
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

    private static String trimTo(String value, int max) {
        if (value == null) {
            return "";
        }
        String t = value.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
