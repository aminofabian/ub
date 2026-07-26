package zelisline.ub.marketplace.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.marketplace.api.dto.CreateMarketplaceSupplierRequest;
import zelisline.ub.marketplace.api.dto.CreateMarketplaceSupplierUserRequest;
import zelisline.ub.marketplace.api.dto.MarketplaceSupplierSummaryResponse;
import zelisline.ub.marketplace.api.dto.MarketplaceSupplierUserRow;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.MarketplaceSupplierStatuses;
import zelisline.ub.marketplace.domain.SupplierUser;
import zelisline.ub.marketplace.domain.SupplierUserRoles;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.marketplace.repository.SupplierUserRepository;
import zelisline.ub.platform.application.PlatformSupplierPortalSettingsService;

@Service
@RequiredArgsConstructor
public class MarketplaceAdminService {

    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final SupplierUserRepository supplierUserRepository;
    private final SupplierIdentityIndexService supplierIdentityIndexService;
    private final PasswordEncoder passwordEncoder;
    private final PlatformSupplierPortalSettingsService portalSettingsService;
    private final SupplierPortalSessionService sessionService;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    @Transactional(readOnly = true)
    public Page<MarketplaceSupplierSummaryResponse> listSuppliers(String q, String status, Pageable pageable) {
        return marketplaceSupplierRepository.search(blankToNull(q), blankToNull(status), pageable)
                .map(this::toSummary);
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
    public MarketplaceSupplierSummaryResponse suspendSupplier(String supplierId, String actorId) {
        MarketplaceSupplier supplier = requireSupplier(supplierId);
        supplier.setStatus(MarketplaceSupplierStatuses.SUSPENDED);
        marketplaceSupplierRepository.save(supplier);
        for (SupplierUser user : supplierUserRepository.findByMarketplaceSupplierIdOrderByCreatedAtAsc(supplierId)) {
            sessionService.revokeAll(user.getId());
        }
        publishOps(
                AuditEventTypes.SUPPLIER_MARKETPLACE_SUSPENDED,
                actorId,
                supplierId,
                null,
                Map.of("marketplaceSupplierId", supplierId));
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

    @Transactional(readOnly = true)
    public List<MarketplaceSupplierUserRow> listPortalUsers(String supplierId) {
        requireSupplier(supplierId);
        return supplierUserRepository.findByMarketplaceSupplierIdOrderByCreatedAtAsc(supplierId).stream()
                .map(MarketplaceAdminService::toUserRow)
                .toList();
    }

    @Transactional
    public MarketplaceSupplierUserRow setPortalUserActive(
            String supplierId,
            String userId,
            boolean active,
            String actorId
    ) {
        SupplierUser user = requireUser(supplierId, userId);
        user.setActive(active);
        if (!active) {
            user.setLockedUntil(null);
            sessionService.revokeAll(user.getId());
        }
        supplierUserRepository.save(user);
        publishOps(
                active
                        ? AuditEventTypes.SUPPLIER_PORTAL_USER_UNSUSPENDED
                        : AuditEventTypes.SUPPLIER_PORTAL_USER_SUSPENDED,
                actorId,
                supplierId,
                userId,
                Map.of("active", active));
        return toUserRow(user);
    }

    @Transactional
    public MarketplaceSupplierUserRow resetPortalUserPassword(
            String supplierId,
            String userId,
            String password,
            String actorId
    ) {
        portalSettingsService.validatePassword(password);
        SupplierUser user = requireUser(supplierId, userId);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        supplierUserRepository.save(user);
        sessionService.revokeAll(user.getId());
        publishOps(
                AuditEventTypes.SUPPLIER_PORTAL_PASSWORD_RESET,
                actorId,
                supplierId,
                userId,
                Map.of());
        return toUserRow(user);
    }

    @Transactional
    public void forceLogoutPortalUser(String supplierId, String userId, String actorId) {
        SupplierUser user = requireUser(supplierId, userId);
        sessionService.revokeAll(user.getId());
        publishOps(
                AuditEventTypes.SUPPLIER_PORTAL_FORCE_LOGOUT,
                actorId,
                supplierId,
                userId,
                Map.of());
    }

    @Transactional
    public MarketplaceSupplierUserRow unlockPortalUser(String supplierId, String userId, String actorId) {
        SupplierUser user = requireUser(supplierId, userId);
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        supplierUserRepository.save(user);
        publishOps(
                AuditEventTypes.SUPPLIER_PORTAL_USER_UNLOCKED,
                actorId,
                supplierId,
                userId,
                Map.of());
        return toUserRow(user);
    }

    private MarketplaceSupplier requireSupplier(String supplierId) {
        return marketplaceSupplierRepository.findById(supplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
    }

    private SupplierUser requireUser(String supplierId, String userId) {
        requireSupplier(supplierId);
        return supplierUserRepository.findByIdAndMarketplaceSupplierId(userId, supplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Portal user not found"));
    }

    private MarketplaceSupplierSummaryResponse toSummary(MarketplaceSupplier supplier) {
        long userCount = supplierUserRepository.countByMarketplaceSupplierId(supplier.getId());
        return new MarketplaceSupplierSummaryResponse(
                supplier.getId(),
                supplier.getName(),
                supplier.getDescription(),
                supplier.getContactEmail(),
                supplier.getStatus(),
                supplier.getContactPhone(),
                supplier.getUsername(),
                userCount);
    }

    private static MarketplaceSupplierUserRow toUserRow(SupplierUser user) {
        return new MarketplaceSupplierUserRow(
                user.getId(),
                user.getMarketplaceSupplierId(),
                user.getEmail(),
                user.getPhone(),
                user.getName(),
                user.getRoleKey(),
                user.isActive(),
                user.getLastLoginAt(),
                user.getLockedUntil(),
                user.getCreatedAt());
    }

    private void publishOps(
            String type,
            String actorId,
            String marketplaceSupplierId,
            String supplierUserId,
            Map<String, Object> extra
    ) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("marketplaceSupplierId", marketplaceSupplierId);
        if (supplierUserId != null) {
            meta.put("supplierUserId", supplierUserId);
        }
        if (extra != null) {
            meta.putAll(extra);
        }
        auditEventPublisher.publish(auditEventBuilder
                .builder(AuditEventCategory.SUPPLIERS, type, AuditEventSeverity.INFO)
                .actor(actorId, actorId != null ? AuditEventActorType.USER : AuditEventActorType.SYSTEM)
                .target("marketplace_supplier", marketplaceSupplierId)
                .source("super_admin")
                .diff(meta)
                .build());
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
