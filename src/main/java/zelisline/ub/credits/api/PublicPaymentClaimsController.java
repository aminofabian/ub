package zelisline.ub.credits.api;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.api.dto.SubmitPublicClaimRequest;
import zelisline.ub.credits.application.PublicPaymentClaimService;

@Validated
@RestController
@RequestMapping("/api/v1/public/credits/payment-claims")
@RequiredArgsConstructor
public class PublicPaymentClaimsController {

    private final PublicPaymentClaimService publicPaymentClaimService;

    /**
     * Unauthenticated submission — token authenticity is possession of plaintext token minted internally.
     */
    @PostMapping("/{plaintextToken}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void submit(
            @PathVariable String plaintextToken,
            @Valid @RequestBody SubmitPublicClaimRequest body
    ) {
        publicPaymentClaimService.submitByTokenPlain(plaintextToken, body.amount(), body.reference());
    }

    /**
     * Unauthenticated fallback submission when the payer only has business id + customer phone.
     */
    @PostMapping("/by-phone/{businessId}/{phone}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void submitByPhone(
            @PathVariable String businessId,
            @PathVariable String phone,
            @Valid @RequestBody SubmitPublicClaimRequest body
    ) {
        publicPaymentClaimService.submitByBusinessAndPhone(businessId, phone, body.amount(), body.reference());
    }
}
