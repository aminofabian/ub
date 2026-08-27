package zelisline.ub.onboarding.sequence.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import zelisline.ub.opsalerts.application.WebOrderPlacedOpsAlertEvent;
import zelisline.ub.platform.realtime.RealtimeBridge;

@Component
@RequiredArgsConstructor
public class MerchantOnboardingEventListener {

    private static final Logger log = LoggerFactory.getLogger(MerchantOnboardingEventListener.class);

    private final MerchantOnboardingSequenceService sequenceService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSaleCompleted(RealtimeBridge.SaleCompletedEvent event) {
        if (event == null || event.businessId() == null) {
            return;
        }
        try {
            sequenceService.onFirstSale(event.businessId());
        } catch (RuntimeException ex) {
            log.warn("onboarding first-sale hook failed businessId={}", event.businessId(), ex);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onShiftOpened(RealtimeBridge.ShiftOpenedEvent event) {
        if (event == null || event.businessId() == null) {
            return;
        }
        try {
            sequenceService.onFirstShiftOpened(event.businessId());
        } catch (RuntimeException ex) {
            log.warn("onboarding first-shift hook failed businessId={}", event.businessId(), ex);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onWebOrderPlaced(WebOrderPlacedOpsAlertEvent event) {
        if (event == null || event.businessId() == null) {
            return;
        }
        try {
            sequenceService.onFirstWebOrder(event.businessId(), event.grandTotal());
        } catch (RuntimeException ex) {
            log.warn("onboarding first-web-order hook failed businessId={}", event.businessId(), ex);
        }
    }
}
