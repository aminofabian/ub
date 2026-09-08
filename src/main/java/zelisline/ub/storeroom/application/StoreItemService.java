package zelisline.ub.storeroom.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.storeroom.api.dto.CreateStoreItemRequest;
import zelisline.ub.storeroom.api.dto.PatchStoreItemRequest;
import zelisline.ub.storeroom.api.dto.StoreItemResponse;
import zelisline.ub.storeroom.domain.StoreItem;
import zelisline.ub.storeroom.repository.StoreItemRepository;

@Service
@RequiredArgsConstructor
public class StoreItemService {

    private final StoreItemRepository storeItemRepository;

    @Transactional(readOnly = true)
    public List<StoreItemResponse> list(String businessId) {
        return storeItemRepository.findByBusinessIdOrderByNameAsc(businessId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public StoreItemResponse create(String businessId, CreateStoreItemRequest request) {
        String barcode = normalizeBarcode(request.barcode());
        assertBarcodeAvailable(businessId, barcode, null);

        StoreItem row = new StoreItem();
        row.setBusinessId(businessId);
        row.setName(request.name().trim());
        row.setBarcode(barcode);
        row.setQuantity(request.quantity());
        row.setExpiryDate(request.expiryDate());
        row.setBuyingPrice(normalizeMoney(request.buyingPrice()));
        storeItemRepository.save(row);
        return toResponse(row);
    }

    @Transactional
    public StoreItemResponse update(String businessId, String id, PatchStoreItemRequest request) {
        StoreItem row = storeItemRepository.findByIdAndBusinessId(id, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Store item not found"));

        if (request.name() != null) {
            String name = request.name().trim();
            if (name.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name cannot be blank");
            }
            row.setName(name);
        }
        if (request.barcode() != null) {
            String barcode = normalizeBarcode(request.barcode());
            assertBarcodeAvailable(businessId, barcode, id);
            row.setBarcode(barcode);
        }
        if (request.quantity() != null) {
            row.setQuantity(request.quantity());
        }
        if (Boolean.TRUE.equals(request.clearExpiryDate())) {
            row.setExpiryDate(null);
        } else if (request.expiryDate() != null) {
            row.setExpiryDate(request.expiryDate());
        }
        if (Boolean.TRUE.equals(request.clearBuyingPrice())) {
            row.setBuyingPrice(null);
        } else if (request.buyingPrice() != null) {
            row.setBuyingPrice(normalizeMoney(request.buyingPrice()));
        }

        storeItemRepository.save(row);
        return toResponse(row);
    }

    @Transactional
    public void delete(String businessId, String id) {
        StoreItem row = storeItemRepository.findByIdAndBusinessId(id, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Store item not found"));
        storeItemRepository.delete(row);
    }

    private void assertBarcodeAvailable(String businessId, String barcode, String excludeId) {
        if (barcode == null) {
            return;
        }
        boolean taken = excludeId == null
                ? storeItemRepository.existsByBusinessIdAndBarcode(businessId, barcode)
                : storeItemRepository.existsByBusinessIdAndBarcodeAndIdNot(businessId, barcode, excludeId);
        if (taken) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Barcode already in use");
        }
    }

    private static String normalizeBarcode(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static BigDecimal normalizeMoney(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private StoreItemResponse toResponse(StoreItem row) {
        return new StoreItemResponse(
                row.getId(),
                row.getName(),
                row.getBarcode(),
                row.getQuantity(),
                row.getExpiryDate(),
                row.getBuyingPrice(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }
}
