package zelisline.ub.kplc.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.kplc.api.dto.PublicKplcTokenResponse;
import zelisline.ub.kplc.domain.CustomerKplcMeter;
import zelisline.ub.kplc.domain.KplcMeterNumbers;
import zelisline.ub.kplc.repository.CustomerKplcMeterRepository;
import zelisline.ub.messaging.application.CustomerMessageDispatcher;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.notifications.domain.DeviceToken;
import zelisline.ub.notifications.infrastructure.ExpoPushSender;
import zelisline.ub.notifications.infrastructure.FcmSender;
import zelisline.ub.notifications.infrastructure.WebPushSender;
import zelisline.ub.notifications.repository.DeviceTokenRepository;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class KplcDepletionAlertService {

    private static final Logger log = LoggerFactory.getLogger(KplcDepletionAlertService.class);
    private static final DateTimeFormatter DAY_LABEL =
            DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private final CustomerKplcMeterRepository meterRepository;
    private final CustomerKplcTokenArchive tokenArchive;
    private final CustomerPhoneRepository customerPhoneRepository;
    private final CustomerRepository customerRepository;
    private final UserRepository userRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final BusinessRepository businessRepository;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final CustomerMessageDispatcher customerMessageDispatcher;
    private final WebPushSender webPushSender;
    private final FcmSender fcmSender;
    private final ExpoPushSender expoPushSender;
    private final Clock clock;

    @Value("${app.kplc.depletion-alerts.zone:Africa/Nairobi}")
    private String zoneId;

    @Transactional
    public int sweep() {
        ZoneId zone = ZoneId.of(zoneId);
        LocalDate today = clock.instant().atZone(zone).toLocalDate();
        Instant now = clock.instant();
        int sent = 0;
        for (CustomerKplcMeter meter : meterRepository.findByDepletionAlertsEnabledTrue()) {
            if (dispatchIfDue(meter, today, now, zone)) {
                sent++;
            }
        }
        return sent;
    }

    private boolean dispatchIfDue(CustomerKplcMeter meter, LocalDate today, Instant now, ZoneId zone) {
        List<PublicKplcTokenResponse> tokens = tokenArchive.list(
                meter.getBusinessId(), meter.getCustomerId(), meter.getMeterNumber());
        var estimate = KplcDepletionEstimator.estimate(tokens, now);
        if (estimate.isEmpty() || estimate.get().alreadyEmpty()) {
            return false;
        }
        LocalDate emptyOn = estimate.get().estimatedEmptyAt().atZone(zone).toLocalDate();
        long daysUntil = ChronoUnit.DAYS.between(today, emptyOn);
        int kind;
        if (daysUntil == 2 && !emptyOn.equals(meter.getLastTwoDayAlertOn())) {
            kind = 2;
        } else if (daysUntil == 1 && !emptyOn.equals(meter.getLastOneDayAlertOn())) {
            kind = 1;
        } else {
            return false;
        }
        String shop = businessRepository.findById(meter.getBusinessId())
                .map(Business::getName)
                .orElse("the shop");
        String message = buildMessage(shop, meter.getMeterNumber(), kind, emptyOn, zone);
        boolean smsOk = sendSms(meter, message);
        int pushSent = pushShopper(meter, shop, message);
        if (!smsOk && pushSent == 0) {
            return false;
        }
        if (kind == 2) {
            meter.setLastTwoDayAlertOn(emptyOn);
        } else {
            meter.setLastOneDayAlertOn(emptyOn);
        }
        meterRepository.save(meter);
        log.info("KPLC depletion alert kind={} meterTail={} emptyOn={} sms={} push={}",
                kind, tail(meter.getMeterNumber()), emptyOn, smsOk, pushSent);
        return true;
    }

    private boolean sendSms(CustomerKplcMeter meter, String message) {
        String phoneDigits = resolvePhone(meter.getCustomerId());
        if (phoneDigits == null) {
            log.warn("KPLC depletion SMS skipped, no phone customer={}", meter.getCustomerId());
            return false;
        }
        TenantMessagingConfig messaging = messagingSettingsService.resolveForDispatch(meter.getBusinessId());
        CustomerMessageDispatcher.DeliveryResult delivery =
                customerMessageDispatcher.deliver(messaging, phoneDigits, message);
        boolean ok = "sent".equals(delivery.outcome()) || "stub".equals(delivery.outcome());
        if (!ok) {
            log.warn("KPLC depletion SMS failed meterTail={} outcome={} detail={}",
                    tail(meter.getMeterNumber()), delivery.outcome(), delivery.detail());
        }
        return ok;
    }

    private int pushShopper(CustomerKplcMeter meter, String shop, String message) {
        String userId = resolveShopperUserId(meter);
        if (userId == null) {
            return 0;
        }
        List<DeviceToken> tokens = deviceTokenRepository.findByBusinessIdAndUserIdAndRevokedAtIsNull(
                meter.getBusinessId(), userId);
        if (tokens.isEmpty()) {
            return 0;
        }
        String title = shop;
        String body = stripShopPrefix(message, shop);
        int webSent = webPushSender.sendToTokens(tokens, title, body, "/");
        int fcmSent = fcmSender.sendToTokens(tokens, title, body);
        int expoSent = expoPushSender.sendToTokens(tokens, title, body);
        return webSent + fcmSent + expoSent;
    }

    private String resolveShopperUserId(CustomerKplcMeter meter) {
        return customerRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(meter.getCustomerId(), meter.getBusinessId())
                .map(Customer::getEmail)
                .filter(email -> email != null && !email.isBlank())
                .map(email -> email.trim().toLowerCase(Locale.ROOT))
                .flatMap(email -> userRepository.findByBusinessIdAndEmailAndDeletedAtIsNull(
                        meter.getBusinessId(), email))
                .map(User::getId)
                .orElse(null);
    }

    static String buildMessage(String shopName, String meterNumber, int daysBefore, LocalDate emptyOn, ZoneId zone) {
        String when = daysBefore == 1 ? "tomorrow" : "in 2 days";
        String meter = formatMeter(meterNumber);
        String day = emptyOn.format(DAY_LABEL);
        return shopName + ": KPLC meter " + meter + " looks like it runs out " + when
                + " (" + day + "). Buy a token before the lights go.";
    }

    static String stripShopPrefix(String message, String shop) {
        String prefix = shop + ": ";
        if (message != null && shop != null && message.startsWith(prefix)) {
            return message.substring(prefix.length());
        }
        return message;
    }

    private String resolvePhone(String customerId) {
        List<CustomerPhone> phones = customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc(customerId);
        if (phones.isEmpty()) {
            return null;
        }
        CustomerPhone pick = phones.stream().filter(CustomerPhone::isPrimary).findFirst().orElse(phones.getFirst());
        return StkPhoneNormalizer.normalize(pick.getPhone());
    }

    private static String formatMeter(String raw) {
        String meter = KplcMeterNumbers.normalize(raw);
        if (meter == null) {
            return raw;
        }
        if (meter.length() < 8) {
            return meter;
        }
        return meter.replaceAll("(\\d{4})(?=\\d)", "$1 ").trim();
    }

    private static String tail(String meter) {
        if (meter == null || meter.length() < 4) {
            return "****";
        }
        return meter.substring(meter.length() - 4);
    }
}
