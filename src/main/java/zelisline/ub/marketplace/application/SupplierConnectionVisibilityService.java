package zelisline.ub.marketplace.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.domain.BusinessSupplierConnection;
import zelisline.ub.marketplace.repository.BusinessSupplierConnectionRepository;
import zelisline.ub.suppliers.api.dto.PatchSupplierPortalVisibilityRequest;
import zelisline.ub.suppliers.api.dto.SupplierPortalVisibilityResponse;
import zelisline.ub.suppliers.repository.SupplierRepository;

/**
 * The shop's side of a supplier connection: what this supplier may see of the shop's records.
 *
 * <p>Every method takes the caller's own {@code businessId} and finds the connection through the shop's
 * local supplier row, so an id from another tenant finds nothing. The defaults are the safe ones in
 * {@link BusinessSupplierConnection}: purchase history on (the portal's order and money views are built on
 * it), everything else off.
 */
@Service
@RequiredArgsConstructor
public class SupplierConnectionVisibilityService {

    private final SupplierRepository supplierRepository;
    private final BusinessSupplierConnectionRepository connectionRepository;

    @Transactional(readOnly = true)
    public SupplierPortalVisibilityResponse get(String businessId, String localSupplierId) {
        requireOwnSupplier(businessId, localSupplierId);
        return connectionRepository.findByLocalSupplierId(localSupplierId)
                .filter(connection -> businessId.equals(connection.getBusinessId()))
                .map(SupplierConnectionVisibilityService::toResponse)
                .orElseGet(SupplierPortalVisibilityResponse::notConnected);
    }

    @Transactional
    public SupplierPortalVisibilityResponse update(
            String businessId,
            String localSupplierId,
            PatchSupplierPortalVisibilityRequest request
    ) {
        requireOwnSupplier(businessId, localSupplierId);
        BusinessSupplierConnection connection = connectionRepository.findByLocalSupplierId(localSupplierId)
                .filter(row -> businessId.equals(row.getBusinessId()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "This supplier is not connected to the supplier portal"));

        if (request.canViewPurchaseHistory() != null) {
            connection.setCanViewPurchaseHistory(request.canViewPurchaseHistory());
        }
        if (request.canViewStockLevels() != null) {
            connection.setCanViewStockLevels(request.canViewStockLevels());
        }
        if (request.canViewLowStockAlerts() != null) {
            connection.setCanViewLowStockAlerts(request.canViewLowStockAlerts());
        }
        if (request.canViewSalesVelocity() != null) {
            connection.setCanViewSalesVelocity(request.canViewSalesVelocity());
        }
        if (request.canViewDemandForecast() != null) {
            connection.setCanViewDemandForecast(request.canViewDemandForecast());
        }
        if (request.canSuggestRestock() != null) {
            connection.setCanSuggestRestock(request.canSuggestRestock());
        }
        if (request.canCreateDraftPo() != null) {
            connection.setCanCreateDraftPo(request.canCreateDraftPo());
        }
        connectionRepository.save(connection);
        return toResponse(connection);
    }

    private void requireOwnSupplier(String businessId, String localSupplierId) {
        if (localSupplierId == null || localSupplierId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found");
        }
        supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(localSupplierId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
    }

    private static SupplierPortalVisibilityResponse toResponse(BusinessSupplierConnection connection) {
        return new SupplierPortalVisibilityResponse(
                true,
                connection.getStatus(),
                connection.isCanViewPurchaseHistory(),
                connection.isCanViewStockLevels(),
                connection.isCanViewLowStockAlerts(),
                connection.isCanViewSalesVelocity(),
                connection.isCanViewDemandForecast(),
                connection.isCanSuggestRestock(),
                connection.isCanCreateDraftPo());
    }
}
