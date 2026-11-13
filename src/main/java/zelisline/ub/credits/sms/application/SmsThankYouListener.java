package zelisline.ub.credits.sms.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.realtime.RealtimeBridge.SaleCompletedEvent;

/**
 * Sends the first-purchase thank-you after a sale commits. Asynchronous and never
 * throws: a messaging problem must not touch the sale.
 */
@Component
@RequiredArgsConstructor
public class SmsThankYouListener {

    private static final Logger log = LoggerFactory.getLogger(SmsThankYouListener.class);

    private final SmsAutomationService automationService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSaleCompleted(SaleCompletedEvent event) {
        try {
            automationService.thankYouForSale(event.businessId(), event.saleId());
        } catch (Exception ex) {
            log.warn("sms thank-you failed sale={}: {}", event.saleId(), ex.getMessage());
        }
    }
}
