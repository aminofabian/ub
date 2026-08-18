package zelisline.ub.credits.api;

import org.springframework.http.HttpStatus;
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
import zelisline.ub.credits.api.dto.ShopperPhoneVerifyRequest;
import zelisline.ub.credits.api.dto.VerifyCustomerPhoneVerificationResponse;
import zelisline.ub.credits.application.CustomerPhoneVerificationService;
import zelisline.ub.tenancy.application.PublicHostBusinessResolver;

@Validated
@RestController
@RequestMapping("/api/v1/public/shopper/auth")
@RequiredArgsConstructor
public class PublicShopperPhoneAuthController {

    private final CustomerPhoneVerificationService phoneVerificationService;
    private final PublicHostBusinessResolver publicHostBusinessResolver;

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
    public VerifyCustomerPhoneVerificationResponse verifyCode(
            @Valid @RequestBody ShopperPhoneVerifyRequest body,
            HttpServletRequest request
    ) {
        return phoneVerificationService.verify(
                publicHostBusinessResolver.resolveOrThrow(request),
                body.phone(),
                body.code());
    }
}
