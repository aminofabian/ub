package zelisline.ub.messaging.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;

/**
 * Sends the guest an online room-booking acknowledgement off the request thread, only once the
 * booking has committed. Mirrors {@link CreditTabPaymentConfirmationListener}: {@code @Async} keeps
 * a slow provider off the guest's HTTP response, and {@code AFTER_COMMIT} means a rolled-back
 * booking never texts anyone. A send failure is logged, never thrown — the booking already stands.
 */
@Component
@RequiredArgsConstructor
public class RoomBookingConfirmationListener {

    private static final Logger log = LoggerFactory.getLogger(RoomBookingConfirmationListener.class);

    private final RoomBookingConfirmationService confirmationService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRoomBooked(RoomBookingConfirmationEvent event) {
        try {
            confirmationService.dispatch(event);
        } catch (Exception ex) {
            log.warn("Room booking confirmation message failed stay={}", event.stayId(), ex);
        }
    }
}
