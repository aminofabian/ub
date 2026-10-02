package zelisline.ub.messaging.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CreditTabPaymentConfirmationListener {

    private static final Logger log = LoggerFactory.getLogger(CreditTabPaymentConfirmationListener.class);

    private final CreditTabPaymentConfirmationService confirmationService;

    @Async
    // fallbackExecution: never drop the confirmation silently if an event is ever
    // published outside a transaction (the ops-alert listener already does this).
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTabPaymentConfirmed(CreditTabPaymentConfirmationEvent event) {
        try {
            confirmationService.dispatch(event);
        } catch (Exception ex) {
            log.warn("Tab payment confirmation SMS failed reference={}", event.referenceId(), ex);
        }
    }
}
