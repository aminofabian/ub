package zelisline.ub.marketplace.application;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.PatchSupplierPortalProfileRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalProfileResponse;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;

@Service
@RequiredArgsConstructor
public class SupplierPortalProfileService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public SupplierPortalProfileResponse getProfile(String marketplaceSupplierId) {
        MarketplaceSupplier supplier = requireSupplier(marketplaceSupplierId);
        return toResponse(supplier);
    }

    @Transactional
    public SupplierPortalProfileResponse updateProfile(
            String marketplaceSupplierId,
            PatchSupplierPortalProfileRequest request
    ) {
        MarketplaceSupplier supplier = requireSupplier(marketplaceSupplierId);
        if (request.description() != null) {
            supplier.setDescription(blankToNull(request.description()));
        }
        if (request.contactEmail() != null) {
            supplier.setContactEmail(blankToNull(request.contactEmail()));
        }
        if (request.contactPhone() != null) {
            supplier.setContactPhone(blankToNull(request.contactPhone()));
        }
        if (request.deliveryRegions() != null) {
            supplier.setDeliveryRegionsJson(writeJson(request.deliveryRegions()));
        }
        if (request.categoryTags() != null) {
            supplier.setCategoryTagsJson(writeJson(request.categoryTags()));
        }
        marketplaceSupplierRepository.save(supplier);
        return toResponse(supplier);
    }

    private MarketplaceSupplier requireSupplier(String marketplaceSupplierId) {
        return marketplaceSupplierRepository.findById(marketplaceSupplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
    }

    private SupplierPortalProfileResponse toResponse(MarketplaceSupplier supplier) {
        return new SupplierPortalProfileResponse(
                supplier.getId(),
                supplier.getName(),
                supplier.getDescription(),
                supplier.getContactEmail(),
                supplier.getContactPhone(),
                supplier.getStatus(),
                readJsonList(supplier.getDeliveryRegionsJson()),
                readJsonList(supplier.getCategoryTagsJson()));
    }

    private List<String> readJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String writeJson(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
