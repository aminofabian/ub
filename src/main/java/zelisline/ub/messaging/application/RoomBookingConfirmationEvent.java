package zelisline.ub.messaging.application;

import java.math.BigDecimal;

/**
 * Published after a guest's online room-booking request opens a BOOKED stay on the front desk
 * (ADR 0031), so the guest gets a "we have your request" SMS/WhatsApp. It is consumed after the
 * booking transaction commits, off the request thread, so a slow or failing messaging provider
 * never blocks or rolls back the booking itself.
 *
 * <p>The phone is the guest's own, captured on the booking form; no customer record is created for
 * an online request, so there is no customer id to resolve a phone from — treat {@code phoneDigits}
 * as the only source.
 *
 * @param stayId    the opened stay/order id — for logs and idempotency only
 * @param guestName the sanitized guest name from the booking form, or null
 * @param roomName  the room let, for the message body
 * @param checkIn   ISO date (yyyy-MM-dd) of arrival
 * @param checkOut  ISO date (yyyy-MM-dd) of departure
 * @param estimate  the estimated room total for the stay, or null
 */
public record RoomBookingConfirmationEvent(
        String businessId,
        String stayId,
        String guestName,
        String phoneDigits,
        String roomName,
        String checkIn,
        String checkOut,
        BigDecimal estimate
) {
}
