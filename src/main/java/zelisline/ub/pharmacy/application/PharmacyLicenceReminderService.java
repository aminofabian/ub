package zelisline.ub.pharmacy.application;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.notifications.application.NotificationService;
import zelisline.ub.pharmacy.domain.PharmacyProfile;
import zelisline.ub.pharmacy.repository.PharmacyProfileRepository;

/**
 * Reminds a pharmacy that its premises licence is about to lapse (ADR 0033): one notification
 * 60 days before the recorded expiry date, one 14 days before, and one when it has passed. The
 * notification key holds the date and the stage, so each is sent once, and a new expiry date
 * starts again. Crosses tenants on purpose: it is a sweep, and each notification is written
 * for the profile's own business only.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PharmacyLicenceReminderService {

    public static final String TYPE_LICENCE_EXPIRY = "pharmacy.licence_expiry";
    static final String STAGE_60 = "60";
    static final String STAGE_14 = "14";
    static final String STAGE_EXPIRED = "expired";
    private static final ZoneId SHOP_ZONE = ZoneId.of("Africa/Nairobi");

    private final PharmacyProfileRepository profileRepository;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public void sweep() {
        LocalDate today = LocalDate.now(SHOP_ZONE);
        for (PharmacyProfile p : profileRepository
                .findByLicenceExpiresOnIsNotNullAndLicenceExpiresOnLessThanEqual(today.plusDays(60))) {
            try {
                remind(p, today);
            } catch (Exception ex) {
                log.warn("Licence reminder skipped businessId={}: {}", p.getBusinessId(), ex.toString());
            }
        }
    }

    /** Which reminder is due for a licence expiring on {@code expiresOn}, or null when none is. */
    static String stage(LocalDate expiresOn, LocalDate today) {
        if (expiresOn == null) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(today, expiresOn);
        if (days < 0) {
            // Stop nagging about a date that passed long ago; the overview still shows it.
            return days >= -30 ? STAGE_EXPIRED : null;
        }
        if (days <= 14) {
            return STAGE_14;
        }
        return days <= 60 ? STAGE_60 : null;
    }

    void remind(PharmacyProfile p, LocalDate today) throws Exception {
        String stage = stage(p.getLicenceExpiresOn(), today);
        if (stage == null) {
            return;
        }
        long days = ChronoUnit.DAYS.between(today, p.getLicenceExpiresOn());
        String body = STAGE_EXPIRED.equals(stage)
                ? "The premises licence date you recorded has passed. Renew it and update the date under Pharmacy, Settings."
                : "Your premises licence expires in " + days + (days == 1 ? " day." : " days.")
                        + " Renew it and update the date under Pharmacy, Settings.";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", "Pharmacy licence");
        payload.put("body", body);
        payload.put("expiresOn", p.getLicenceExpiresOn().toString());
        payload.put("actionUrl", "/pharmacy");
        notificationService.tryInsertDedupe(
                p.getBusinessId(),
                TYPE_LICENCE_EXPIRY,
                "pharmacy.licence:" + p.getBusinessId() + ":" + p.getLicenceExpiresOn() + ":" + stage,
                "operational",
                STAGE_60.equals(stage) ? "MEDIUM" : "HIGH",
                objectMapper.writeValueAsString(payload));
    }
}
