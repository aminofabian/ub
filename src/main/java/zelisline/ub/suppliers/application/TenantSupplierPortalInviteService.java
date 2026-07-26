package zelisline.ub.suppliers.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.CreateSupplierPortalInviteRequest;
import zelisline.ub.marketplace.api.dto.CreateSupplierPortalInviteResponse;
import zelisline.ub.marketplace.application.SupplierIdentityIndexService;
import zelisline.ub.marketplace.application.SupplierPortalInviteService;
import zelisline.ub.marketplace.domain.BusinessSupplierConnection;
import zelisline.ub.marketplace.domain.BusinessSupplierConnectionStatuses;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.MarketplaceSupplierStatuses;
import zelisline.ub.marketplace.repository.BusinessSupplierConnectionRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.platform.application.PlatformSupplierPortalSettingsService;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierSlug;
import zelisline.ub.suppliers.repository.SupplierContactRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;

@Service
@RequiredArgsConstructor
public class TenantSupplierPortalInviteService {

    private final SupplierRepository supplierRepository;
    private final SupplierContactRepository supplierContactRepository;
    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final BusinessSupplierConnectionRepository connectionRepository;
    private final SupplierIdentityIndexService identityIndexService;
    private final SupplierPortalInviteService inviteService;
    private final PlatformSupplierPortalSettingsService portalSettingsService;

    @Transactional
    public CreateSupplierPortalInviteResponse invite(
            String businessId,
            String localSupplierId,
            String actorUserId,
            boolean sendSms
    ) {
        portalSettingsService.requireClaimEnabled();
        Supplier local = supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(localSupplierId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));

        String marketplaceId = local.getMarketplaceSupplierId();
        if (marketplaceId == null || marketplaceId.isBlank()) {
            marketplaceId = provisionMarketplaceIdentity(local);
        } else {
            ensureActiveConnection(businessId, marketplaceId, local.getId());
        }

        String phone = resolveInvitePhone(local);
        return inviteService.createInvite(
                marketplaceId,
                new CreateSupplierPortalInviteRequest(phone, sendSms),
                actorUserId);
    }

    private String provisionMarketplaceIdentity(Supplier local) {
        MarketplaceSupplier marketplace = new MarketplaceSupplier();
        marketplace.setName(local.getName());
        marketplace.setContactPhone(normalizePhone(local.getPayoutPhone()));
        marketplace.setStatus(MarketplaceSupplierStatuses.ACTIVE);
        marketplace.setUsername(allocateUsername(local.getName(), local.getCode()));
        marketplaceSupplierRepository.save(marketplace);
        identityIndexService.upsertMarketplaceSupplier(marketplace);

        local.setMarketplaceSupplierId(marketplace.getId());
        supplierRepository.save(local);
        identityIndexService.upsertTenantSupplier(local, marketplace.getContactPhone(), null);

        ensureActiveConnection(local.getBusinessId(), marketplace.getId(), local.getId());
        return marketplace.getId();
    }

    private void ensureActiveConnection(String businessId, String marketplaceId, String localSupplierId) {
        if (connectionRepository.existsByBusinessIdAndMarketplaceSupplierId(businessId, marketplaceId)) {
            return;
        }
        if (connectionRepository.existsByLocalSupplierIdAndStatus(
                localSupplierId, BusinessSupplierConnectionStatuses.ACTIVE)) {
            return;
        }
        BusinessSupplierConnection connection = new BusinessSupplierConnection();
        connection.setBusinessId(businessId);
        connection.setMarketplaceSupplierId(marketplaceId);
        connection.setLocalSupplierId(localSupplierId);
        connection.setStatus(BusinessSupplierConnectionStatuses.ACTIVE);
        connection.setCanViewPurchaseHistory(true);
        connectionRepository.save(connection);
    }

    private String resolveInvitePhone(Supplier local) {
        String phone = normalizePhone(local.getPayoutPhone());
        if (phone != null) {
            return phone;
        }
        return supplierContactRepository.findBySupplierIdOrderByPrimaryContactDescNameAsc(local.getId()).stream()
                .map(c -> normalizePhone(c.getPhone()))
                .filter(p -> p != null)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Add a payout phone or contact phone before inviting"));
    }

    private String allocateUsername(String name, String code) {
        String base = SupplierSlug.slugify(name);
        if (base.length() < 2) {
            base = SupplierSlug.slugify(code != null ? code : "supplier");
        }
        String candidate = base;
        int i = 0;
        while (marketplaceSupplierRepository.existsByUsernameIgnoreCase(candidate)) {
            i += 1;
            candidate = base + "-" + i;
            if (i > 50) {
                candidate = base + "-" + java.util.UUID.randomUUID().toString().substring(0, 6);
                break;
            }
        }
        return candidate;
    }

    private static String normalizePhone(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return StkPhoneNormalizer.normalize(raw);
    }
}
