package zelisline.ub.support.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.storefront.application.ShopperIdentifyService;
import zelisline.ub.support.api.dto.GuestThreadDto;

/**
 * Transcript recovery for guests who lost their thread token (cleared storage,
 * new device, or an order card that landed in a phone-keyed thread).
 *
 * <p>One transaction covers "consume the OTP token" and "merge the threads", so
 * a failed recovery (e.g. no chat for that number) does not burn the visitor's
 * single-use verification token — they can correct the number and retry.
 */
@Service
@RequiredArgsConstructor
public class GuestThreadRecoveryService {

    private final ShopperIdentifyService shopperIdentifyService;
    private final SupportService supportService;

    @Transactional
    public GuestThreadDto recover(
            String type,
            String businessId,
            String guestId,
            String phone,
            String phoneVerificationToken
    ) {
        String verifiedPhone = shopperIdentifyService.consumeVerifiedPhone(phone, phoneVerificationToken);
        return supportService.guestRecover(type, businessId, guestId, verifiedPhone);
    }
}
