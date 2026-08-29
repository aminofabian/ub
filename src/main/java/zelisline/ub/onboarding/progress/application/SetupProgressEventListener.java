package zelisline.ub.onboarding.progress.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.realtime.RealtimeBridge;

@Component
@RequiredArgsConstructor
public class SetupProgressEventListener {

    private static final Logger log = LoggerFactory.getLogger(SetupProgressEventListener.class);

    private final SetupProgressInvalidatePublisher invalidatePublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSaleCompleted(RealtimeBridge.SaleCompletedEvent event) {
        invalidate(event == null ? null : event.businessId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSupplyPosted(RealtimeBridge.SupplyPostedEvent event) {
        invalidate(event == null ? null : event.businessId());
    }

    private void invalidate(String businessId) {
        if (businessId == null || businessId.isBlank()) {
            return;
        }
        try {
            invalidatePublisher.invalidate(businessId);
        } catch (RuntimeException ex) {
            log.debug("setup progress invalidate skipped businessId={}", businessId, ex);
        }
    }
}
