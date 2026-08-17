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
import zelisline.ub.credits.api.dto.PublicPayerClaimLookupRequest;
import zelisline.ub.credits.api.dto.PublicPayerClaimLookupResponse;
import zelisline.ub.credits.api.dto.PublicPayerClaimSendRequest;
import zelisline.ub.credits.api.dto.PublicPayerClaimVerifyRequest;
import zelisline.ub.credits.api.dto.PublicPayerClaimVerifyResponse;
import zelisline.ub.credits.api.dto.SendCustomerPhoneVerificationResponse;
import zelisline.ub.credits.application.PayerPhoneClaimService;
import zelisline.ub.tenancy.application.PublicHostBusinessResolver;

@Validated
@RestController
@RequestMapping("/api/v1/public/credits/payer-claims")
@RequiredArgsConstructor
public class PublicPayerClaimController {

    private final PayerPhoneClaimService payerPhoneClaimService;
    private final PublicHostBusinessResolver publicHostBusinessResolver;

    @PostMapping("/lookup")
    public PublicPayerClaimLookupResponse lookup(
            @Valid @RequestBody PublicPayerClaimLookupRequest body,
            HttpServletRequest request
    ) {
        return payerPhoneClaimService.lookup(
                publicHostBusinessResolver.resolveOrThrow(request),
                body.firstName(),
                body.lastName(),
                body.lastThree());
    }

    @PostMapping("/send-code")
    @ResponseStatus(HttpStatus.CREATED)
    public SendCustomerPhoneVerificationResponse sendCode(
            @Valid @RequestBody PublicPayerClaimSendRequest body,
            HttpServletRequest request
    ) {
        return payerPhoneClaimService.sendPublic(
                publicHostBusinessResolver.resolveOrThrow(request),
                body.firstName(),
                body.lastName(),
                body.missingDigits(),
                body.lastThree());
    }

    @PostMapping("/verify")
    public PublicPayerClaimVerifyResponse verify(
            @Valid @RequestBody PublicPayerClaimVerifyRequest body,
            HttpServletRequest request
    ) {
        return payerPhoneClaimService.verifyPublic(
                publicHostBusinessResolver.resolveOrThrow(request),
                body.firstName(),
                body.lastName(),
                body.missingDigits(),
                body.lastThree(),
                body.code());
    }
}
