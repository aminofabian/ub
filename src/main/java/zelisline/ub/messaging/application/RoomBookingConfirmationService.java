package zelisline.ub.messaging.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Builds and sends the online room-booking acknowledgement. Resolves the per-business messaging
 * config exactly as the tab-payment confirmation does, bails quietly when nothing is configured,
 * and lets {@link CustomerMessageDispatcher} pick WhatsApp-then-SMS. The body carries no money owed
 * or ID — just the room, the dates, and that the house will confirm by phone.
 */
@Service
@RequiredArgsConstructor
public class RoomBookingConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(RoomBookingConfirmationService.class);
    private static final DateTimeFormatter PRETTY = DateTimeFormatter.ofPattern("d MMM", Locale.UK);

    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final CustomerMessageDispatcher customerMessageDispatcher;
    private final BusinessRepository businessRepository;

    public void dispatch(RoomBookingConfirmationEvent event) {
        TenantMessagingConfig messaging = messagingSettingsService.resolveForTest(event.businessId());
        if (!messaging.secretsReadable()) {
            log.info("Skip room booking confirmation — messaging secrets unreadable stay={}", event.stayId());
            return;
        }
        if (!messaging.smsConfigured() && !messaging.metaWhatsAppConfigured()) {
            log.info("Skip room booking confirmation — no SMS or WhatsApp configured stay={}", event.stayId());
            return;
        }

        String phoneDigits = StkPhoneNormalizer.normalize(event.phoneDigits());
        if (phoneDigits == null || phoneDigits.isBlank()) {
            log.info("Skip room booking confirmation — no usable phone stay={}", event.stayId());
            return;
        }

        Business business = businessRepository.findById(event.businessId()).orElse(null);
        String currency = business != null && business.getCurrency() != null
                ? business.getCurrency().trim()
                : "KES";
        String shopName = business != null && business.getName() != null
                ? business.getName().trim()
                : "the house";

        String message = buildMessage(event.guestName(), shopName, event.roomName(),
                event.checkIn(), event.checkOut(), event.estimate(), currency);

        CustomerMessageDispatcher.DeliveryResult delivery =
                customerMessageDispatcher.deliver(messaging, phoneDigits, message);
        // Guest phone is PII — log the channel and outcome, never the number or the name.
        log.info("room_booking_confirmation stay={} channel={} outcome={} detail={}",
                event.stayId(), delivery.channel(), delivery.outcome(), delivery.detail());
    }

    static String buildMessage(String guestName, String shopName, String roomName,
            String checkIn, String checkOut, BigDecimal estimate, String currency) {
        StringBuilder sb = new StringBuilder();
        String greeting = (guestName == null || guestName.isBlank()) ? "Hi" : "Hi " + guestName.trim();
        sb.append(greeting).append(",\n\n");
        sb.append(shopName).append(" has your booking request");
        String room = roomName == null || roomName.isBlank() ? null : roomName.trim();
        if (room != null) {
            sb.append(" for ").append(room);
        }
        String stay = prettyRange(checkIn, checkOut);
        if (stay != null) {
            sb.append(" (").append(stay).append(")");
        }
        sb.append(".");
        if (estimate != null && estimate.signum() > 0) {
            sb.append("\nEstimated room total: ").append(formatMoney(estimate, currency)).append(".");
        }
        sb.append("\n\nThe house will confirm with you by phone. Thank you!");
        return sb.toString();
    }

    /** "12 Jan – 14 Jan", or null if either date is unparseable. */
    private static String prettyRange(String checkIn, String checkOut) {
        LocalDate in = parse(checkIn);
        LocalDate out = parse(checkOut);
        if (in == null || out == null) {
            return null;
        }
        return in.format(PRETTY) + " – " + out.format(PRETTY);
    }

    private static LocalDate parse(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(iso.trim());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String formatMoney(BigDecimal amount, String currency) {
        BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_UP);
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.UK);
        nf.setMinimumFractionDigits(scaled.remainder(BigDecimal.ONE).signum() != 0 ? 2 : 0);
        nf.setMaximumFractionDigits(2);
        return currency + " " + nf.format(scaled);
    }
}
