package zelisline.ub.marketplace.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.GlobalSupplierHubResponse;
import zelisline.ub.marketplace.api.dto.GlobalSupplierHubShopCard;
import zelisline.ub.marketplace.api.dto.GlobalSupplierHubTotals;
import zelisline.ub.marketplace.domain.BusinessSupplierConnection;
import zelisline.ub.marketplace.domain.BusinessSupplierConnectionStatuses;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.repository.BusinessSupplierConnectionRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.suppliers.api.dto.SupplierPurchaseHistorySummary;
import zelisline.ub.suppliers.application.SupplierPurchaseHistoryService;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierSlug;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.domain.DomainMapping;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@Service
@RequiredArgsConstructor
public class GlobalSupplierHubService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final BusinessSupplierConnectionRepository connectionRepository;
    private final SupplierRepository supplierRepository;
    private final BusinessRepository businessRepository;
    private final DomainMappingRepository domainMappingRepository;
    private final SupplierPurchaseHistoryService purchaseHistoryService;

    @Transactional(readOnly = true)
    public GlobalSupplierHubResponse byUsername(String usernameRaw) {
        String username = SupplierPortalProfileService.normalizeUsername(usernameRaw);
        if (username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found");
        }
        MarketplaceSupplier marketplace = marketplaceSupplierRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
        return forMarketplaceSupplier(marketplace);
    }

    @Transactional(readOnly = true)
    public GlobalSupplierHubResponse forMarketplaceSupplierId(String marketplaceSupplierId) {
        MarketplaceSupplier marketplace = marketplaceSupplierRepository.findById(marketplaceSupplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
        return forMarketplaceSupplier(marketplace);
    }

    private GlobalSupplierHubResponse forMarketplaceSupplier(MarketplaceSupplier marketplace) {
        List<BusinessSupplierConnection> links = connectionRepository.findByMarketplaceSupplierIdAndStatus(
                marketplace.getId(), BusinessSupplierConnectionStatuses.ACTIVE);

        BigDecimal totalOwed = ZERO;
        BigDecimal totalPaid = ZERO;
        BigDecimal totalPending = ZERO;
        String currency = "KES";
        List<GlobalSupplierHubShopCard> shops = new ArrayList<>();

        for (BusinessSupplierConnection link : links) {
            Supplier local = supplierRepository
                    .findByIdAndDeletedAtIsNull(link.getLocalSupplierId())
                    .orElse(null);
            if (local == null) {
                continue;
            }
            Business business = businessRepository.findById(link.getBusinessId()).orElse(null);
            String shopName = business != null && business.getName() != null
                    ? business.getName().trim()
                    : "Shop";
            if (business != null && business.getCurrency() != null && !business.getCurrency().isBlank()) {
                currency = business.getCurrency().trim();
            }

            var history = purchaseHistoryService.purchaseHistory(link.getBusinessId(), local.getId(), 1);
            SupplierPurchaseHistorySummary summary = history.summary();

            BigDecimal owed = money(summary.openBalance());
            BigDecimal paid = money(summary.totalPaid());
            BigDecimal pending = money(summary.partialOpenBalance());
            LocalDate lastSupply = summary.lastInvoiceDate();

            totalOwed = totalOwed.add(owed);
            totalPaid = totalPaid.add(paid);
            totalPending = totalPending.add(pending);

            String slug = SupplierSlug.canonical(local.getName(), local.getCode());
            shops.add(new GlobalSupplierHubShopCard(
                    link.getBusinessId(),
                    shopName,
                    resolveSlugHost(link.getBusinessId()),
                    local.getId(),
                    owed,
                    paid,
                    pending,
                    lastSupply,
                    "/s/" + slug));
        }

        shops.sort(Comparator
                .comparing(GlobalSupplierHubShopCard::lastSupplyAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(GlobalSupplierHubShopCard::shopName, String.CASE_INSENSITIVE_ORDER));

        return new GlobalSupplierHubResponse(
                marketplace.getUsername(),
                marketplace.getName(),
                shops.size(),
                currency,
                new GlobalSupplierHubTotals(money(totalOwed), money(totalPaid), money(totalPending)),
                List.copyOf(shops));
    }

    private String resolveSlugHost(String businessId) {
        List<DomainMapping> domains =
                domainMappingRepository.findByBusinessIdAndDeletedAtIsNull(businessId);
        String host = null;
        for (DomainMapping d : domains) {
            if (!d.isActive()) {
                continue;
            }
            if (d.isPrimary()) {
                host = d.getDomain();
                break;
            }
            if (host == null) {
                host = d.getDomain();
            }
        }
        if (host != null && !host.isBlank()) {
            return host.trim().toLowerCase();
        }
        return null;
    }

    private static BigDecimal money(BigDecimal n) {
        if (n == null) {
            return ZERO;
        }
        return n.setScale(2, RoundingMode.HALF_UP);
    }
}
