package zelisline.ub.sales.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.realtime.RealtimeBridge.ShiftClosedEvent;

/**
 * Sends the shift-close stock and sales emails after the close has committed.
 * A mail failure never reopens the shift.
 */
@Component
@RequiredArgsConstructor
public class ShiftCloseReportListener {

    private static final Logger log = LoggerFactory.getLogger(ShiftCloseReportListener.class);

    private final ShiftCloseReportService shiftCloseReportService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onShiftClosed(ShiftClosedEvent event) {
        try {
            shiftCloseReportService.send(event);
        } catch (Exception ex) {
            log.warn("Shift close report failed shift={}", event.shiftId(), ex);
        }
    }
}
