package zelisline.ub.marketplace.application;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
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
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.identity.application.TokenHasher;
import zelisline.ub.marketplace.api.dto.CreateSupplierPortalInviteRequest;
import zelisline.ub.marketplace.api.dto.CreateSupplierPortalInviteResponse;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.SupplierPortalClaimInvite;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.marketplace.repository.SupplierPortalClaimInviteRepository;
import zelisline.ub.marketplace.repository.SupplierUserRepository;
import zelisline.ub.messaging.application.CustomerMessageDispatcher;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.platform.application.PlatformSupplierPortalSettingsService;
import zelisline.ub.platform.domain.PlatformSupplierPortalSettings;

@Service
@RequiredArgsConstructor
public class SupplierPortalInviteService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final char[] INVITE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int INVITE_CODE_LENGTH = 8;

    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final SupplierPortalClaimInviteRepository inviteRepository;
    private final SupplierUserRepository supplierUserRepository;
    private final PlatformSupplierPortalSettingsService portalSettingsService;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final CustomerMessageDispatcher customerMessageDispatcher;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    @Transactional
    public CreateSupplierPortalInviteResponse createInvite(
            String marketplaceSupplierId,
            CreateSupplierPortalInviteRequest request,
            String actorId
    ) {
        portalSettingsService.requireClaimEnabled();
        PlatformSupplierPortalSettings settings = portalSettingsService.loadSingleton();

        MarketplaceSupplier marketplace = marketplaceSupplierRepository.findById(marketplaceSupplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));

        String phone = null;
        if (request != null && request.phone() != null && !request.phone().isBlank()) {
            phone = StkPhoneNormalizer.normalize(request.phone());
            if (phone == null || phone.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid phone number");
            }
            if (supplierUserRepository.existsByPhone(phone)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "This phone already has a portal account");
            }
        }

        Instant now = Instant.now();
        String code = generateInviteCode();
        SupplierPortalClaimInvite invite = new SupplierPortalClaimInvite();
        invite.setMarketplaceSupplierId(marketplace.getId());
        invite.setCodeHash(TokenHasher.sha256Hex(code));
        invite.setPhone(phone);
        invite.setExpiresAt(now.plus(Duration.ofMinutes(settings.getCodeExpiryMinutes())));
        invite.setAttempts(0);
        invite.setMaxAttempts(settings.getMaxAttempts());
        invite.setCreatedByActorId(actorId);
        invite.setCreatedAt(now);
        inviteRepository.save(invite);

        boolean sendSms = request != null && Boolean.TRUE.equals(request.sendSms());
        boolean smsSent = false;
        if (sendSms) {
            if (phone == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phone is required to send SMS");
            }
            TenantMessagingConfig messaging = messagingSettingsService.resolvePlatformForContactReply();
            if (!messaging.enabled() || (!messaging.smsConfigured() && !messaging.metaWhatsAppConfigured())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Messaging is not configured to send invitations");
            }
            Map<String, String> vars = portalSettingsService.templateVariables(
                    marketplace.getName(),
                    "Palmart",
                    code,
                    settings.getCodeExpiryMinutes());
            String template = settings.getInvitationMessageTemplate();
            if (template == null || template.isBlank()) {
                template = settings.getSmsTemplate();
            }
            String message = portalSettingsService.renderTemplate(
                    template != null ? template : "Your claim code is {{claim_code}}. Visit {{portal_url}}",
                    vars);
            var delivery = customerMessageDispatcher.deliver(messaging, phone, message);
            if (!"sent".equals(delivery.outcome()) && !"stub".equals(delivery.outcome())) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not send invitation SMS");
            }
            invite.setLastSentAt(now);
            inviteRepository.save(invite);
            smsSent = true;
        }

        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("inviteId", invite.getId());
        diff.put("phone", phone);
        diff.put("smsSent", smsSent);
        diff.put("expiresAt", invite.getExpiresAt().toString());
        auditEventPublisher.publish(auditEventBuilder
                .builder(AuditEventCategory.SUPPLIERS, AuditEventTypes.SUPPLIER_PORTAL_INVITE_CREATED, AuditEventSeverity.INFO)
                .actor(actorId, actorId != null ? AuditEventActorType.USER : AuditEventActorType.SYSTEM)
                .target("marketplace_supplier", marketplace.getId())
                .targetLabel(marketplace.getName())
                .source("super_admin")
                .diff(diff)
                .build());

        String claimUrl = portalSettingsService.claimUrl(phone);
        return new CreateSupplierPortalInviteResponse(
                invite.getId(),
                marketplace.getId(),
                code,
                phone,
                invite.getExpiresAt(),
                smsSent,
                claimUrl);
    }

    private static String generateInviteCode() {
        char[] out = new char[INVITE_CODE_LENGTH];
        for (int i = 0; i < INVITE_CODE_LENGTH; i++) {
            out[i] = INVITE_ALPHABET[SECURE_RANDOM.nextInt(INVITE_ALPHABET.length)];
        }
        return new String(out);
    }
}
