package zelisline.ub.payments.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@RestController
@RequestMapping("/api/v1/payments/mpesa/stk")
@RequiredArgsConstructor
@Validated
public class MpesaStkIntentApiController {

    private final MpesaStkIntentService mpesaStkIntentService;

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
}
