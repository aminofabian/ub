package zelisline.ub.payments.application;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.PosStkPushResponse;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PlatformKioskPaySettings;
import zelisline.ub.payments.domain.StkPushContextType;

/**
 * Cashier POS STK via platform Kiosk Pay KopoKopo (funds land in merchant Kiosk Pay wallet).
 */
@Service
@RequiredArgsConstructor
public class KioskPayPosStkService {

    private final KioskPayWalletService walletService;
    private final PlatformKioskPaySettingsService platformSettings;
    private final GatewayStkPushService gatewayStkPushService;
    private final StkPushRetryHelper stkPushRetryHelper;

    public PosStkPushResponse push(
            String businessId,
            String phoneNumber,
            BigDecimal amount,
            String reference,
            String description
    ) {
        if (!walletService.isPosCollectEnabled(businessId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Kiosk Pay is not available for POS on this business");
        }
        Map<String, String> creds = platformSettings.kopokopoCredentials()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Platform KopoKopo credentials for Kiosk Pay are not configured"));

        String phone = phoneNumber != null ? phoneNumber.trim() : "";
        if (phone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "phoneNumber is required");
        }
        String desc = description != null && !description.isBlank()
                ? description.trim()
                : "Kiosk Pay POS";

        PaymentGatewayStkService.StkPushOutcome outcome =
                stkPushRetryHelper.initiateWithCredentialsAfterClearingPhone(
                        businessId,
                        GatewayType.KOPOKOPO.name(),
                        PlatformKioskPaySettings.PLATFORM_KOPOKOPO_CONFIG_ID,
                        creds,
                        phone,
                        amount,
                        reference,
                        desc);

        if (outcome.accepted() && outcome.checkoutRequestId() != null) {
            gatewayStkPushService.registerPush(
                    businessId,
                    GatewayType.KOPOKOPO,
                    PlatformKioskPaySettings.PLATFORM_KOPOKOPO_CONFIG_ID,
                    outcome.checkoutRequestId(),
                    reference,
                    StkPushContextType.POS_PAYMENT,
                    null,
                    amount,
                    phone);
        }

        return new PosStkPushResponse(
                outcome.accepted(),
                outcome.checkoutRequestId(),
                outcome.message(),
                outcome.responseCode());
    }
}
