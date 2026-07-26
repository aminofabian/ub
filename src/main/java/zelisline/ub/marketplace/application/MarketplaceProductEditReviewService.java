package zelisline.ub.marketplace.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.MarketplaceProductEditRequestRow;
import zelisline.ub.marketplace.api.dto.ReviewMarketplaceProductEditRequest;
import zelisline.ub.marketplace.domain.BusinessSupplierConnection;
import zelisline.ub.marketplace.domain.BusinessSupplierConnectionStatuses;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.MarketplaceSupplierProduct;
import zelisline.ub.marketplace.domain.MarketplaceSupplierProductEditRequest;
import zelisline.ub.marketplace.repository.BusinessSupplierConnectionRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierProductEditRequestRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierProductRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;

@Service
@RequiredArgsConstructor
public class MarketplaceProductEditReviewService {

    private final MarketplaceSupplierProductEditRequestRepository editRequestRepository;
    private final BusinessSupplierConnectionRepository connectionRepository;
    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final MarketplaceSupplierProductRepository productRepository;
    private final SupplierPortalCatalogService catalogService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<MarketplaceProductEditRequestRow> listPendingForBusiness(String businessId) {
        List<String> supplierIds = connectionRepository
                .findByBusinessIdAndStatus(businessId, BusinessSupplierConnectionStatuses.ACTIVE)
                .stream()
                .map(BusinessSupplierConnection::getMarketplaceSupplierId)
                .distinct()
                .toList();
        if (supplierIds.isEmpty()) {
            return List.of();
        }
        return editRequestRepository
                .findByMarketplaceSupplierIdInAndStatusOrderByCreatedAtDesc(
                        supplierIds, MarketplaceSupplierProductEditRequest.PENDING)
                .stream()
                .map(this::toRow)
                .toList();
    }

    @Transactional
    public MarketplaceProductEditRequestRow approve(
            String businessId,
            String userId,
            String editId,
            ReviewMarketplaceProductEditRequest body
    ) {
        MarketplaceSupplierProductEditRequest edit = requirePendingForBusiness(businessId, editId);
        catalogService.applyProposedEdit(edit);
        edit.setStatus(MarketplaceSupplierProductEditRequest.APPROVED);
        edit.setReviewedAt(Instant.now());
        edit.setReviewedByUserId(userId);
        edit.setReviewedBusinessId(businessId);
        if (body != null && body.note() != null && !body.note().isBlank()) {
            edit.setReviewNote(body.note().trim());
        }
        editRequestRepository.save(edit);
        catalogService.notifyEditDecision(edit, true);
        return toRow(edit);
    }

    @Transactional
    public MarketplaceProductEditRequestRow reject(
            String businessId,
            String userId,
            String editId,
            ReviewMarketplaceProductEditRequest body
    ) {
        MarketplaceSupplierProductEditRequest edit = requirePendingForBusiness(businessId, editId);
        edit.setStatus(MarketplaceSupplierProductEditRequest.REJECTED);
        edit.setReviewedAt(Instant.now());
        edit.setReviewedByUserId(userId);
        edit.setReviewedBusinessId(businessId);
        if (body != null && body.note() != null && !body.note().isBlank()) {
            edit.setReviewNote(body.note().trim());
        }
        editRequestRepository.save(edit);
        catalogService.notifyEditDecision(edit, false);
        return toRow(edit);
    }

    private MarketplaceSupplierProductEditRequest requirePendingForBusiness(String businessId, String editId) {
        MarketplaceSupplierProductEditRequest edit = editRequestRepository.findById(editId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Edit request not found"));
        if (!MarketplaceSupplierProductEditRequest.PENDING.equals(edit.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Edit request is no longer pending");
        }
        boolean linked = connectionRepository
                .findByBusinessIdAndMarketplaceSupplierId(businessId, edit.getMarketplaceSupplierId())
                .filter(c -> BusinessSupplierConnectionStatuses.ACTIVE.equals(c.getStatus()))
                .isPresent();
        if (!linked) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not connected to this supplier");
        }
        return edit;
    }

    private MarketplaceProductEditRequestRow toRow(MarketplaceSupplierProductEditRequest edit) {
        String supplierName = marketplaceSupplierRepository.findById(edit.getMarketplaceSupplierId())
                .map(MarketplaceSupplier::getName)
                .orElse("Supplier");
        String productName = productRepository.findById(edit.getProductId())
                .map(MarketplaceSupplierProduct::getName)
                .orElse(edit.getProductId());
        return new MarketplaceProductEditRequestRow(
                edit.getId(),
                edit.getMarketplaceSupplierId(),
                supplierName,
                edit.getProductId(),
                productName,
                edit.getStatus(),
                readMap(edit.getProposedJson()),
                readMap(edit.getLiveSnapshotJson()),
                edit.getCreatedAt(),
                edit.getReviewedAt(),
                edit.getReviewNote());
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }
}
