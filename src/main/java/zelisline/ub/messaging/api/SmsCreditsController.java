package zelisline.ub.messaging.api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.messaging.api.dto.SmsCreditBalanceResponse;
import zelisline.ub.messaging.api.dto.SmsCreditLedgerResponse;
import zelisline.ub.messaging.api.dto.SmsCreditPurchaseDtos;
import zelisline.ub.messaging.application.SmsCreditPurchaseService;
import zelisline.ub.messaging.application.SmsCreditService;
import zelisline.ub.messaging.domain.SmsCreditLedgerEntry;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * Tenant SMS credits API — balance is visible to any authenticated staff; ledger
 * and purchase need the corresponding permissions (SMS_CREDITS_SCOPE.md §10, §14).
 */
@Validated
@RestController
@RequestMapping("/api/v1/sms-credits")
@RequiredArgsConstructor
public class SmsCreditsController {

    private final SmsCreditService creditService;
    private final SmsCreditPurchaseService purchaseService;

    @GetMapping("/balance")
    public SmsCreditBalanceResponse balance(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return creditService.getBalanceView(TenantRequestIds.resolveBusinessId(request));
    }

    @GetMapping("/ledger")
    @PreAuthorize("hasPermission(null, 'sms.credits.ledger.read')")
    public SmsCreditLedgerResponse ledger(
            HttpServletRequest request,
            @RequestParam(defaultValue = "50") int limit
    ) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        List<SmsCreditLedgerResponse.SmsCreditLedgerRow> rows = creditService.ledger(businessId, limit).stream()
                .map(SmsCreditsController::toLedgerRow)
                .toList();
        return new SmsCreditLedgerResponse(rows);
    }

    @PostMapping("/purchase")
    @PreAuthorize("hasPermission(null, 'sms.credits.purchase')")
    public SmsCreditPurchaseDtos.SmsCreditPurchaseResponse purchase(
            HttpServletRequest request,
            @Valid @RequestBody SmsCreditPurchaseDtos.SmsCreditPurchaseRequest body
    ) {
        CurrentTenantUser.require(request);
        return purchaseService.initiate(
                TenantRequestIds.resolveBusinessId(request),
                body.credits(),
                body.phone());
    }

    @GetMapping("/purchases/{id}")
    @PreAuthorize("hasPermission(null, 'sms.credits.purchase')")
    public SmsCreditPurchaseDtos.SmsCreditPurchaseStatusResponse purchaseStatus(
            HttpServletRequest request,
            @PathVariable String id
    ) {
        CurrentTenantUser.require(request);
        return purchaseService.status(TenantRequestIds.resolveBusinessId(request), id);
    }

    private static SmsCreditLedgerResponse.SmsCreditLedgerRow toLedgerRow(SmsCreditLedgerEntry e) {
        return new SmsCreditLedgerResponse.SmsCreditLedgerRow(
                e.getId(),
                e.getDelta(),
                e.getBalanceAfter(),
                e.getKind(),
                e.getReason(),
                e.getReferenceId(),
                e.getCreatedAt(),
                e.getCreatedByUserId());
    }
}
