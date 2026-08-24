package zelisline.ub.platform.adoption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
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
            notifier.notifyDomainPurchased(event.businessId(), businessName(event.businessId()), event.fqdn());
        } catch (Exception ex) {
            log.warn("Domain purchase SMS failed business={}",
                    event != null ? event.businessId() : null, ex);
        }
    }

    private String businessName(String businessId) {
        return businessRepository.findByIdAndDeletedAtIsNull(businessId)
                .map(Business::getName)
                .orElse(null);
    }
}
