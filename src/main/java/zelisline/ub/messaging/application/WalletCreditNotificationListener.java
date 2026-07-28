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
public class WalletCreditNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(WalletCreditNotificationListener.class);

    private final WalletCreditNotificationService walletCreditNotificationService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWalletCreditNotification(WalletCreditNotificationEvent event) {
        try {
            walletCreditNotificationService.dispatch(event);
        } catch (Exception ex) {
            log.warn("Wallet credit notification failed saleId={}", event.saleId(), ex);
        }
    }
}
