package zelisline.ub.marketplace.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.SupplierPortalPaymentRow;
import zelisline.ub.marketplace.domain.BusinessSupplierConnection;
import zelisline.ub.marketplace.domain.BusinessSupplierConnectionStatuses;
import zelisline.ub.marketplace.repository.BusinessSupplierConnectionRepository;
import zelisline.ub.purchasing.domain.SupplierPayment;
import zelisline.ub.purchasing.repository.SupplierPaymentRepository;
import zelisline.ub.suppliers.application.SupplierPurchaseHistoryService;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class SupplierPortalPaymentsService {

    private static final int DEFAULT_LIMIT = 100;

    private final BusinessSupplierConnectionRepository connectionRepository;
    private final SupplierPaymentRepository supplierPaymentRepository;
    private final BusinessRepository businessRepository;
    private final SupplierPurchaseHistoryService purchaseHistoryService;
    private final SupplierPortalShopLinkService shopLinkService;

    @Transactional
    public List<SupplierPortalPaymentRow> listPayments(String marketplaceSupplierId, String localSupplierIdFilter) {
        try {
            shopLinkService.ensureLinksAndCatalogue(marketplaceSupplierId);
        } catch (RuntimeException ignored) {
            // Soft heal.
        }
        List<BusinessSupplierConnection> links = connectionRepository
                .findByMarketplaceSupplierIdAndStatus(marketplaceSupplierId, BusinessSupplierConnectionStatuses.ACTIVE)
                .stream()
                .filter(link -> localSupplierIdFilter == null
                        || localSupplierIdFilter.isBlank()
                        || localSupplierIdFilter.equals(link.getLocalSupplierId()))
                .toList();
        if (links.isEmpty()) {
            return List.of();
        }

        Map<String, BusinessSupplierConnection> byLocal = new HashMap<>();
        for (BusinessSupplierConnection link : links) {
            byLocal.put(link.getLocalSupplierId(), link);
        }

        List<SupplierPayment> payments = supplierPaymentRepository.findBySupplierIdInOrderByPaidAtDesc(
                byLocal.keySet(), PageRequest.of(0, DEFAULT_LIMIT));

        Map<String, java.math.BigDecimal> openByLocal = new HashMap<>();
        for (BusinessSupplierConnection link : links) {
            var summary = purchaseHistoryService
                    .purchaseHistory(link.getBusinessId(), link.getLocalSupplierId(), 1)
                    .summary();
            openByLocal.put(link.getLocalSupplierId(), summary.openBalance());
        }

        return payments.stream()
                .map(p -> {
                    BusinessSupplierConnection link = byLocal.get(p.getSupplierId());
                    String businessName = link == null
                            ? "Shop"
                            : businessRepository.findById(link.getBusinessId())
                                    .map(b -> b.getName() != null ? b.getName() : "Shop")
                                    .orElse("Shop");
                    return new SupplierPortalPaymentRow(
                            p.getId(),
                            p.getBusinessId(),
                            businessName,
                            p.getSupplierId(),
                            p.getPaidAt(),
                            p.getPaymentMethod(),
                            p.getAmount(),
                            p.getReference(),
                            p.getStatus(),
                            openByLocal.get(p.getSupplierId()));
                })
                .toList();
    }
}
