package zelisline.ub.credits.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.api.dto.SendCustomerPhoneVerificationResponse;
import zelisline.ub.credits.api.dto.ShopperPhoneSendRequest;
import zelisline.ub.credits.api.dto.ShopperPhoneSessionRequest;
import zelisline.ub.credits.api.dto.ShopperPhoneSessionResponse;
import zelisline.ub.credits.api.dto.ShopperPhoneVerifyRequest;
import zelisline.ub.credits.api.dto.ShopperPhoneVerifyResponse;
import zelisline.ub.credits.application.CustomerPhoneVerificationService;
import zelisline.ub.identity.application.RefreshTokenCookieSupport;
import zelisline.ub.platform.pageseal.application.PageSealService;
import zelisline.ub.storefront.application.ShopperPhoneSessionService;
import zelisline.ub.tenancy.application.PublicHostBusinessResolver;

@Validated
@RestController
@RequestMapping("/api/v1/public/shopper/auth")
@RequiredArgsConstructor
public class PublicShopperPhoneAuthController {

    private final CustomerPhoneVerificationService phoneVerificationService;
    private final PageSealService pageSealService;
    private final ShopperPhoneSessionService shopperPhoneSessionService;
    private final PublicHostBusinessResolver publicHostBusinessResolver;
    private final RefreshTokenCookieSupport refreshTokenCookieSupport;

    @PostMapping("/send-code")
    @ResponseStatus(HttpStatus.CREATED)
    public SendCustomerPhoneVerificationResponse sendCode(
            @Valid @RequestBody ShopperPhoneSendRequest body,
            HttpServletRequest request
    ) {
        return phoneVerificationService.sendForShopper(
                publicHostBusinessResolver.resolveOrThrow(request),
                body.phone());
    }

    @PostMapping("/verify-code")
    public ShopperPhoneVerifyResponse verifyCode(
            @Valid @RequestBody ShopperPhoneVerifyRequest body,
            HttpServletRequest request
    ) {
        String businessId = publicHostBusinessResolver.resolveOrThrow(request);
        var verified = phoneVerificationService.verify(businessId, body.phone(), body.code());
        return new ShopperPhoneVerifyResponse(
                verified.phoneVerificationToken(),
                verified.expiresAt(),
                pageSealService.customerTabHasPin(businessId, body.phone()),
                pageSealService.customerTabDisplayName(businessId, body.phone()));
    }

    @PostMapping("/session")
    public ResponseEntity<ShopperPhoneSessionResponse> session(
            @Valid @RequestBody ShopperPhoneSessionRequest body,
            HttpServletRequest request
    ) {
        String businessId = publicHostBusinessResolver.resolveOrThrow(request);
        ShopperPhoneSessionResponse tokens = shopperPhoneSessionService.complete(businessId, body, request);
        if (!refreshTokenCookieSupport.isEnabled()) {
            return ResponseEntity.ok(tokens);
        }
        ShopperPhoneSessionResponse bodyWithoutRefresh = new ShopperPhoneSessionResponse(
                tokens.accessToken(),
                null,
                tokens.user(),
                tokens.tabPhone(),
                tokens.unlockToken(),
                tokens.pinCreated());
        return ResponseEntity.ok()
                .headers(refreshTokenCookieSupport.cookieHeaders(tokens.refreshToken()))
                .body(bodyWithoutRefresh);
    }
}
