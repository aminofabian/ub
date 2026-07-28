package zelisline.ub.messaging.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import zelisline.ub.grocery.application.RemoteGroceryInvoiceNotifyEvent;

@Component
@RequiredArgsConstructor
public class RemoteGroceryInvoiceNotifyListener {

    private static final Logger log = LoggerFactory.getLogger(RemoteGroceryInvoiceNotifyListener.class);

    private final RemoteGroceryInvoiceNotifyService remoteGroceryInvoiceNotifyService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRemoteGroceryInvoiceNotify(RemoteGroceryInvoiceNotifyEvent event) {
        try {
            remoteGroceryInvoiceNotifyService.dispatch(event);
        } catch (Exception ex) {
            log.warn("Remote invoice notify failed invoiceId={}", event.invoiceId(), ex);
        }
    }
}
