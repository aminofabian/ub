package zelisline.ub.payments.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.PosStkPushResponse;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.KioskPayAccount;
import zelisline.ub.payments.domain.PlatformKioskPaySettings;
import zelisline.ub.payments.domain.StkPushContextType;

/**
 * Merchant funding their own Kiosk Pay wallet by M-Pesa.
 *
 * <p>Every other path into the wallet is money a customer paid; this is the one
 * place a merchant puts their own float in, which is what makes airtime resale
 * possible before they have taken any Kiosk Pay collections.
 */
@Service
@RequiredArgsConstructor
public class KioskPayTopUpService {

    /** Below this, KopoKopo's own fee makes the top-up pointless. */
    private static final BigDecimal MIN_TOPUP = new BigDecimal("10");

    private final KioskPayWalletService walletService;
    private final PlatformKioskPaySettingsService platformSettings;
    private final GatewayStkPushService gatewayStkPushService;
    private final StkPushRetryHelper stkPushRetryHelper;

    public PosStkPushResponse topUp(
            String businessId,
            String phoneNumber,
            BigDecimal amount,
            String reference
    ) {
        PlatformKioskPaySettings settings = platformSettings.requireEnabledSettings();
        Map<String, String> creds = platformSettings.kopokopoCredentials()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Platform KopoKopo credentials are not configured for top-ups"));

        KioskPayAccount account = walletService.getOrCreate(businessId);
        if (!account.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Activate Kiosk Pay before topping up your wallet");
        }

        String raw = phoneNumber != null && !phoneNumber.isBlank()
                ? phoneNumber.trim()
                : account.getPayoutPhone();
        String phone = StkPhoneNormalizer.normalize(raw);
        if (phone == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A valid M-Pesa phone number is required (e.g. 0712345678)");
        }

        // KopoKopo STK takes whole shillings only.
        BigDecimal charged = amount == null ? null : amount.setScale(0, RoundingMode.HALF_UP);
        if (charged == null || charged.compareTo(MIN_TOPUP) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Minimum top-up is " + settings.getCurrency() + " " + MIN_TOPUP.toPlainString());
        }

        PaymentGatewayStkService.StkPushOutcome outcome =
                stkPushRetryHelper.initiateWithCredentialsAfterClearingPhone(
                        businessId,
                        GatewayType.KOPOKOPO.name(),
                        PlatformKioskPaySettings.PLATFORM_KOPOKOPO_CONFIG_ID,
                        creds,
                        phone,
                        charged,
                        reference,
                        "Kiosk Pay wallet top-up");

        if (outcome.accepted() && outcome.checkoutRequestId() != null) {
            gatewayStkPushService.registerPush(
                    businessId,
                    GatewayType.KOPOKOPO,
                    PlatformKioskPaySettings.PLATFORM_KOPOKOPO_CONFIG_ID,
                    outcome.checkoutRequestId(),
                    reference,
                    StkPushContextType.KIOSK_PAY_TOPUP,
                    null,
                    charged,
                    phone);
        }

        return new PosStkPushResponse(
                outcome.accepted(),
                outcome.checkoutRequestId(),
                outcome.message(),
                outcome.responseCode());
    }
}
