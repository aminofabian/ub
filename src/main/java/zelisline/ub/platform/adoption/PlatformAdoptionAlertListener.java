package zelisline.ub.platform.adoption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.application.DomainPurchaseService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Fires platform-ops SMS after paid tenant adoptions commit, so the super admin
 * can attend to the tenant. Mirrors {@code TenantOpsAlertListener}: async,
 * after-commit, best-effort (never fails the underlying transaction).
 */
@Component
@RequiredArgsConstructor
public class PlatformAdoptionAlertListener {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdoptionAlertListener.class);

    private final PlatformAdoptionSmsNotifier notifier;
    private final BusinessRepository businessRepository;
    private final DomainPurchaseService domainPurchaseService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onKioskPayActivated(KioskPayActivatedEvent event) {
        try {
            if (event == null || event.businessId() == null || event.businessId().isBlank()) {
                return;
            }
            log.info("Adoption event kiosk_pay_activated business={}", event.businessId());
            notifier.notifyKioskPayActivated(event.businessId(), businessName(event.businessId()));
        } catch (Exception ex) {
            log.warn("Kiosk Pay activation SMS failed business={}",
                    event != null ? event.businessId() : null, ex);
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDomainPurchased(DomainPurchasedEvent event) {
        try {
            if (event == null || event.businessId() == null || event.businessId().isBlank()
                    || event.fqdn() == null || event.fqdn().isBlank()) {
                return;
            }
            log.info("Adoption event domain_purchased business={} fqdn={}", event.businessId(), event.fqdn());
            if (event.orderId() != null && !event.orderId().isBlank()) {
                domainPurchaseService.continueAfterPayment(event.orderId());
            }
            notifier.notifyDomainPurchased(
                    event.businessId(),
                    businessName(event.businessId()),
                    event.fqdn(),
                    event.payerPhone(),
                    event.priceCents(),
                    event.paymentCollected());
        } catch (Exception ex) {
            log.warn("Domain purchase SMS failed business={}",
                    event != null ? event.businessId() : null, ex);
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDomainLive(DomainLiveEvent event) {
        try {
            if (event == null || event.fqdn() == null || event.fqdn().isBlank()) {
                return;
            }
            log.info("Adoption event domain_live business={} fqdn={}", event.businessId(), event.fqdn());
            notifier.notifyDomainLive(businessName(event.businessId()), event.fqdn(), event.payerPhone());
        } catch (Exception ex) {
            log.warn("Domain live SMS failed business={}",
                    event != null ? event.businessId() : null, ex);
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDomainHelpRequested(DomainHelpRequestedEvent event) {
        try {
            if (event == null || event.businessId() == null || event.businessId().isBlank()) {
                return;
            }
            log.info("Adoption event domain_help business={} kind={}", event.businessId(), event.kindLabel());
            notifier.notifyDomainHelpRequested(
                    businessName(event.businessId()),
                    event.kindLabel(),
                    event.phone(),
                    event.domain(),
                    event.note(),
                    event.feeCents());
        } catch (Exception ex) {
            log.warn("Domain help SMS failed business={}",
                    event != null ? event.businessId() : null, ex);
        }
    }

    private String businessName(String businessId) {
        if (businessId == null || businessId.isBlank()) {
            return null;
        }
        return businessRepository.findByIdAndDeletedAtIsNull(businessId)
                .map(Business::getName)
                .orElse(null);
    }
}
