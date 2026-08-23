package zelisline.ub.storefront.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.api.dto.SendCustomerPhoneVerificationResponse;
import zelisline.ub.credits.api.dto.ShopperPhoneSendRequest;
import zelisline.ub.credits.api.dto.ShopperPhoneVerifyRequest;
import zelisline.ub.credits.api.dto.VerifyCustomerPhoneVerificationResponse;
import zelisline.ub.storefront.api.dto.ShopperShopsRequest;
import zelisline.ub.storefront.application.ShopperIdentifyService;
import zelisline.ub.tenancy.api.dto.PublicShopsSearchResponse;

/**
 * Phase 4 apex "one door": tenant-agnostic phone identification (§8, §13).
 * The apex never authenticates — it verifies a phone once platform-wide and
 * returns the shops that phone has ordered from, so the apex can forward to
 * the right shop host. Registered in {@code PublicAuthEndpoints} so the JWT
 * filter lets the requests through; the platform-agnostic OTP reuses the
 * existing per-phone cooldown and per-challenge attempt limits.
 */
@RestController
@RequestMapping("/api/v1/public/shopper/auth")
@RequiredArgsConstructor
public class ShopperIdentifyController {

    private final ShopperIdentifyService shopperIdentifyService;

    @PostMapping("/identify/send-code")
    @ResponseStatus(HttpStatus.CREATED)
    public SendCustomerPhoneVerificationResponse sendCode(
            @Valid @RequestBody ShopperPhoneSendRequest body) {
        return shopperIdentifyService.sendCode(body.phone());
    }

    @PostMapping("/identify/verify-code")
    public VerifyCustomerPhoneVerificationResponse verifyCode(
            @Valid @RequestBody ShopperPhoneVerifyRequest body) {
        return shopperIdentifyService.verifyCode(body.phone(), body.code());
    }

    @PostMapping("/shops")
    public List<PublicShopsSearchResponse> shops(
            @Valid @RequestBody ShopperShopsRequest body) {
        return shopperIdentifyService.shops(body.phone(), body.phoneVerificationToken());
    }
}
