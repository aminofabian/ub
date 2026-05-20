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
public class CreditSaleReminderListener {

    private static final Logger log = LoggerFactory.getLogger(CreditSaleReminderListener.class);

    private final CreditSaleReminderService creditSaleReminderService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreditSaleReminder(CreditSaleReminderEvent event) {
        try {
            creditSaleReminderService.dispatch(event);
        } catch (Exception ex) {
            log.warn("Credit sale reminder failed saleId={}", event.saleId(), ex);
        }
    }
}
