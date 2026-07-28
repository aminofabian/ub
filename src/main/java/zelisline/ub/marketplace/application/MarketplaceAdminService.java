package zelisline.ub.marketplace.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

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
import zelisline.ub.marketplace.api.dto.MarketplaceSupplierShopLinkRow;
import zelisline.ub.marketplace.api.dto.MarketplaceSupplierStatsResponse;
import zelisline.ub.marketplace.api.dto.MarketplaceSupplierSummaryResponse;
import zelisline.ub.marketplace.api.dto.MarketplaceSupplierUserRow;
import zelisline.ub.marketplace.domain.BusinessSupplierConnection;
import zelisline.ub.marketplace.domain.BusinessSupplierConnectionStatuses;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.MarketplaceSupplierStatuses;
import zelisline.ub.marketplace.domain.SupplierUser;
import zelisline.ub.marketplace.domain.SupplierUserRoles;
import zelisline.ub.marketplace.repository.BusinessSupplierConnectionRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.marketplace.repository.SupplierUserRepository;
import zelisline.ub.platform.application.PlatformSupplierPortalSettingsService;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class MarketplaceAdminService {

    private static final int SHOP_NAME_PREVIEW_LIMIT = 3;

    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final SupplierUserRepository supplierUserRepository;
    private final BusinessSupplierConnectionRepository connectionRepository;
    private final SupplierRepository supplierRepository;
    private final BusinessRepository businessRepository;
    private final MarketplaceSupplierPassportService passportService;
    private final PasswordEncoder passwordEncoder;
    private final PlatformSupplierPortalSettingsService portalSettingsService;
    private final SupplierPortalSessionService sessionService;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    @Transactional(readOnly = true)
    public Page<MarketplaceSupplierSummaryResponse> listSuppliers(String q, String status, Pageable pageable) {
        Page<MarketplaceSupplier> page = marketplaceSupplierRepository.search(blankToNull(q), blankToNull(status), pageable);
        List<String> ids = page.getContent().stream().map(MarketplaceSupplier::getId).toList();
        Enrichment enrichment = enrich(ids);
        return page.map(supplier -> toSummary(supplier, enrichment));
    }

    @Transactional(readOnly = true)
    public MarketplaceSupplierStatsResponse stats() {
        long total = marketplaceSupplierRepository.count();
        long active = marketplaceSupplierRepository.countByStatus(MarketplaceSupplierStatuses.ACTIVE);
        long draft = marketplaceSupplierRepository.countByStatus(MarketplaceSupplierStatuses.DRAFT);
        long suspended = marketplaceSupplierRepository.countByStatus(MarketplaceSupplierStatuses.SUSPENDED);
        long withPortalUsers = supplierUserRepository.countDistinctMarketplaceSuppliers();
        long withLinkedShops = connectionRepository.countDistinctMarketplaceSuppliersByStatus(
                BusinessSupplierConnectionStatuses.ACTIVE);
        long needingInvite = Math.max(0, total - withPortalUsers);
        return new MarketplaceSupplierStatsResponse(
                total,
                active,
                draft,
                suspended,
                withPortalUsers,
                withLinkedShops,
                needingInvite);
    }

    @Transactional(readOnly = true)
    public List<MarketplaceSupplierShopLinkRow> listShopLinks(String supplierId) {
        requireSupplier(supplierId);
        List<BusinessSupplierConnection> connections =
                connectionRepository.findByMarketplaceSupplierIdOrderByCreatedAtAsc(supplierId);
        if (connections.isEmpty()) {
            return List.of();
        }
        Map<String, Business> businesses = businessRepository
                .findAllById(connections.stream().map(BusinessSupplierConnection::getBusinessId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Business::getId, Function.identity()));
        Map<String, Supplier> locals = supplierRepository
                .findAllById(connections.stream().map(BusinessSupplierConnection::getLocalSupplierId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Supplier::getId, Function.identity()));
        return connections.stream()
                .map(conn -> {
                    Business business = businesses.get(conn.getBusinessId());
                    Supplier local = locals.get(conn.getLocalSupplierId());
                    return new MarketplaceSupplierShopLinkRow(
                            conn.getId(),
                            conn.getBusinessId(),
                            business != null ? business.getName() : "Unknown shop",
                            business != null ? business.getSlug() : null,
                            conn.getLocalSupplierId(),
                            local != null ? local.getName() : "Unknown local supplier",
                            local != null ? local.getStatus() : null,
                            conn.getStatus(),
                            conn.getCreatedAt());
                })
                .toList();
    }

    @Transactional
    public MarketplaceSupplierSummaryResponse createSupplier(CreateMarketplaceSupplierRequest request) {
        MarketplaceSupplier supplier = new MarketplaceSupplier();
        supplier.setName(request.name().trim());
        supplier.setDescription(blankToNull(request.description()));
        supplier.setContactEmail(blankToNull(request.contactEmail()));
        supplier.setContactPhone(SupplierIdentityNormalizer.normalizePhone(request.contactPhone()));
        supplier.setStatus(MarketplaceSupplierStatuses.DRAFT);
        passportService.ensureNumberAndIndex(supplier);
        return toSummary(supplier, Enrichment.empty());
    }

    @Transactional
    public MarketplaceSupplierSummaryResponse activateSupplier(String supplierId) {
        MarketplaceSupplier supplier = requireSupplier(supplierId);
        supplier.setStatus(MarketplaceSupplierStatuses.ACTIVE);
        marketplaceSupplierRepository.save(supplier);
        return toSummary(supplier, enrich(List.of(supplierId)));
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
        return toSummary(supplier, enrich(List.of(supplierId)));
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
        String roleKey = SupplierUserRoles.ADMIN;
        if (request.roleKey() != null && !request.roleKey().isBlank()) {
            try {
                roleKey = SupplierUserRoles.normalize(request.roleKey());
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
            }
        }
        user.setRoleKey(roleKey);
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

    private Enrichment enrich(List<String> supplierIds) {
        if (supplierIds == null || supplierIds.isEmpty()) {
            return Enrichment.empty();
        }
        Map<String, Long> portalUserCounts = new HashMap<>();
        Map<String, Instant> lastLogins = new HashMap<>();
        for (SupplierUser user : supplierUserRepository.findByMarketplaceSupplierIdIn(supplierIds)) {
            portalUserCounts.merge(user.getMarketplaceSupplierId(), 1L, Long::sum);
            Instant login = user.getLastLoginAt();
            if (login != null) {
                lastLogins.merge(user.getMarketplaceSupplierId(), login, (a, b) -> a.isAfter(b) ? a : b);
            }
        }

        List<BusinessSupplierConnection> connections = connectionRepository.findByMarketplaceSupplierIdIn(supplierIds);
        Map<String, List<BusinessSupplierConnection>> bySupplier = connections.stream()
                .collect(Collectors.groupingBy(BusinessSupplierConnection::getMarketplaceSupplierId));

        List<String> businessIds = connections.stream()
                .map(BusinessSupplierConnection::getBusinessId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<String, Business> businesses = businessIds.isEmpty()
                ? Map.of()
                : businessRepository.findAllById(businessIds).stream()
                        .collect(Collectors.toMap(Business::getId, Function.identity()));

        Map<String, Long> shopCounts = new HashMap<>();
        Map<String, List<String>> shopNames = new HashMap<>();
        for (Map.Entry<String, List<BusinessSupplierConnection>> entry : bySupplier.entrySet()) {
            List<BusinessSupplierConnection> active = entry.getValue().stream()
                    .filter(c -> BusinessSupplierConnectionStatuses.ACTIVE.equals(c.getStatus()))
                    .sorted(Comparator.comparing(BusinessSupplierConnection::getCreatedAt))
                    .toList();
            shopCounts.put(entry.getKey(), (long) active.size());
            List<String> names = new ArrayList<>();
            for (BusinessSupplierConnection conn : active) {
                if (names.size() >= SHOP_NAME_PREVIEW_LIMIT) {
                    break;
                }
                Business business = businesses.get(conn.getBusinessId());
                if (business != null && business.getName() != null && !business.getName().isBlank()) {
                    names.add(business.getName());
                }
            }
            shopNames.put(entry.getKey(), names);
        }

        return new Enrichment(portalUserCounts, shopCounts, shopNames, lastLogins);
    }

    private MarketplaceSupplierSummaryResponse toSummary(MarketplaceSupplier supplier, Enrichment enrichment) {
        String id = supplier.getId();
        return new MarketplaceSupplierSummaryResponse(
                id,
                supplier.getSupplierNumber(),
                supplier.getName(),
                supplier.getDescription(),
                supplier.getContactEmail(),
                supplier.getStatus(),
                supplier.getContactPhone(),
                supplier.getUsername(),
                enrichment.portalUserCounts().getOrDefault(id, 0L),
                enrichment.shopCounts().getOrDefault(id, 0L),
                enrichment.shopNames().getOrDefault(id, List.of()),
                supplier.getCreatedAt(),
                supplier.getUpdatedAt(),
                enrichment.lastLogins().get(id));
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

    private record Enrichment(
            Map<String, Long> portalUserCounts,
            Map<String, Long> shopCounts,
            Map<String, List<String>> shopNames,
            Map<String, Instant> lastLogins
    ) {
        static Enrichment empty() {
            return new Enrichment(Map.of(), Map.of(), Map.of(), Map.of());
        }
    }
}
