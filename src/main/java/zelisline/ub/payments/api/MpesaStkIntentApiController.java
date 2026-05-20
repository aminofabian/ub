package zelisline.ub.payments.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.api.dto.MpesaStkInitiateRequest;
import zelisline.ub.credits.api.dto.MpesaStkIntentResponse;
import zelisline.ub.credits.application.MpesaStkIntentService;
import zelisline.ub.credits.domain.MpesaStkIntent;
import zelisline.ub.payments.api.dto.PosStkPushRequest;
import zelisline.ub.payments.api.dto.PosStkPushResponse;
import zelisline.ub.payments.application.PaymentGatewayStkService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@RestController
@RequestMapping("/api/v1/payments/mpesa/stk")
@RequiredArgsConstructor
@Validated
public class MpesaStkIntentApiController {

    private final MpesaStkIntentService mpesaStkIntentService;
    private final PaymentGatewayStkService paymentGatewayStkService;

    @PostMapping("/intents")
    @PreAuthorize("hasPermission(null, 'payments.stk.initiate')")
    @ResponseStatus(HttpStatus.CREATED)
    public MpesaStkIntentResponse initiate(
            @Valid @RequestBody MpesaStkInitiateRequest body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        MpesaStkIntent row =
                mpesaStkIntentService.initiate(TenantRequestIds.resolveBusinessId(request),
                        body.customerId(),
                        body.amount(),
                        idempotencyKey.trim());
        return new MpesaStkIntentResponse(
                row.getId(),
                row.getCheckoutRequestId(),
                row.getStatus(),
                row.getAmount());
    }

    /**
     * POS/cashier STK push: sends M-Pesa prompt to an explicit phone number (sale payment).
     * Does not create a wallet top-up intent.
     */
    @PostMapping("/push")
    @PreAuthorize("hasPermission(null, 'payments.stk.initiate')")
    @ResponseStatus(HttpStatus.CREATED)
    public PosStkPushResponse push(
            @Valid @RequestBody PosStkPushRequest body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String phone = body.phoneNumber() != null ? body.phoneNumber().trim() : "";
        if (phone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "phoneNumber is required");
        }
        String reference = idempotencyKey.trim();
        String description = body.description() != null && !body.description().isBlank()
                ? body.description().trim()
                : "POS payment";
        PaymentGatewayStkService.StkPushOutcome outcome = paymentGatewayStkService.initiate(
                businessId,
                null,
                phone,
                body.amount(),
                reference,
                description);
        return new PosStkPushResponse(
                outcome.accepted(),
                outcome.checkoutRequestId(),
                outcome.message(),
                outcome.responseCode());
    }
}
