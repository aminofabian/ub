package zelisline.ub.marketplace.application;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.CreateMarketplaceSupplierRequest;
import zelisline.ub.marketplace.api.dto.CreateMarketplaceSupplierUserRequest;
import zelisline.ub.marketplace.api.dto.MarketplaceSupplierSummaryResponse;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.MarketplaceSupplierStatuses;
import zelisline.ub.marketplace.domain.SupplierUser;
import zelisline.ub.marketplace.domain.SupplierUserRoles;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.marketplace.repository.SupplierUserRepository;

@Service
@RequiredArgsConstructor
public class MarketplaceAdminService {

    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final SupplierUserRepository supplierUserRepository;
    private final SupplierIdentityIndexService supplierIdentityIndexService;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public Page<MarketplaceSupplierSummaryResponse> listSuppliers(String q, String status, Pageable pageable) {
        return marketplaceSupplierRepository.search(blankToNull(q), blankToNull(status), pageable)
                .map(MarketplaceAdminService::toSummary);
    }

    @Transactional
    public MarketplaceSupplierSummaryResponse createSupplier(CreateMarketplaceSupplierRequest request) {
        MarketplaceSupplier supplier = new MarketplaceSupplier();
        supplier.setName(request.name().trim());
        supplier.setDescription(blankToNull(request.description()));
        supplier.setContactEmail(blankToNull(request.contactEmail()));
        supplier.setContactPhone(SupplierIdentityNormalizer.normalizePhone(request.contactPhone()));
        supplier.setStatus(MarketplaceSupplierStatuses.DRAFT);
        marketplaceSupplierRepository.save(supplier);
        supplierIdentityIndexService.upsertMarketplaceSupplier(supplier);
        return toSummary(supplier);
    }

    @Transactional
    public MarketplaceSupplierSummaryResponse activateSupplier(String supplierId) {
        MarketplaceSupplier supplier = requireSupplier(supplierId);
        supplier.setStatus(MarketplaceSupplierStatuses.ACTIVE);
        marketplaceSupplierRepository.save(supplier);
        return toSummary(supplier);
    }

    @Transactional
    public void createPortalUser(String supplierId, CreateMarketplaceSupplierUserRequest request) {
        MarketplaceSupplier supplier = requireSupplier(supplierId);
        String email = request.email().trim().toLowerCase();
        if (supplierUserRepository.findByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A supplier user with this email already exists");
        }
        SupplierUser user = new SupplierUser();
        user.setMarketplaceSupplierId(supplier.getId());
        user.setEmail(email);
        user.setName(request.name().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRoleKey(SupplierUserRoles.ADMIN);
        supplierUserRepository.save(user);
    }

    private MarketplaceSupplier requireSupplier(String supplierId) {
        return marketplaceSupplierRepository.findById(supplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
    }

    private static MarketplaceSupplierSummaryResponse toSummary(MarketplaceSupplier supplier) {
        return new MarketplaceSupplierSummaryResponse(
                supplier.getId(),
                supplier.getName(),
                supplier.getDescription(),
                supplier.getContactEmail(),
                supplier.getStatus());
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
