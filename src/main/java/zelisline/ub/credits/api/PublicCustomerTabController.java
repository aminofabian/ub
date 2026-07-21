package zelisline.ub.credits.api;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.api.dto.PublicCustomerTabResponse;
import zelisline.ub.credits.api.dto.PublicTabStkRequest;
import zelisline.ub.credits.api.dto.PublicTabStkResponse;
import zelisline.ub.credits.application.PublicCustomerTabService;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * Public customer tab portal: balance owed, purchase history, and M-Pesa STK paydown.
 * Tenant is resolved from Host / {@code X-Tenant-Id}. Phone is the public path key
 * (SMS/WhatsApp link possession + STK-to-registered-phone). Does not reopen claim-by-phone
 * (ADR-0010).
 */
@Validated
@RestController
@RequestMapping("/api/v1/public/credits/tabs")
@RequiredArgsConstructor
public class PublicCustomerTabController {

    private final PublicCustomerTabService publicCustomerTabService;

    @GetMapping("/{phone}")
    public PublicCustomerTabResponse overview(
            @PathVariable String phone,
            HttpServletRequest request
    ) {
        return publicCustomerTabService.overview(TenantRequestIds.resolveBusinessId(request), phone);
    }

    @PostMapping("/{phone}/stk")
    @ResponseStatus(HttpStatus.CREATED)
    public PublicTabStkResponse initiateStk(
            @PathVariable String phone,
            @Valid @RequestBody PublicTabStkRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request
    ) {
        return publicCustomerTabService.initiateStk(
                TenantRequestIds.resolveBusinessId(request),
                phone,
                body.amount(),
                idempotencyKey);
    }

    @GetMapping("/{phone}/stk/{intentId}")
    public PublicTabStkResponse stkStatus(
            @PathVariable String phone,
            @PathVariable String intentId,
            HttpServletRequest request
    ) {
        return publicCustomerTabService.stkStatus(
                TenantRequestIds.resolveBusinessId(request),
                phone,
                intentId);
    }
}
