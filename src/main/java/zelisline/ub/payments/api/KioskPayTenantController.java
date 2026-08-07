package zelisline.ub.payments.api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.KioskPayAccountResponse;
import zelisline.ub.payments.api.dto.KioskPayLedgerEntryResponse;
import zelisline.ub.payments.api.dto.KioskPayWithdrawRequest;
import zelisline.ub.payments.api.dto.KioskPayWithdrawalResponse;
import zelisline.ub.payments.api.dto.UpdateKioskPayAccountRequest;
import zelisline.ub.payments.application.KioskPayWalletService;
import zelisline.ub.payments.application.KioskPayWithdrawService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/payments/kiosk-pay")
@RequiredArgsConstructor
public class KioskPayTenantController {

    private final KioskPayWalletService walletService;
    private final KioskPayWithdrawService withdrawService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'payments.gateways.read')")
    public KioskPayAccountResponse getAccount(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return walletService.getAccount(TenantRequestIds.resolveBusinessId(request));
    }

    @PatchMapping
    @PreAuthorize("hasPermission(null, 'payments.gateways.write')")
    public KioskPayAccountResponse updateAccount(
            HttpServletRequest request,
            @Valid @RequestBody UpdateKioskPayAccountRequest body
    ) {
        CurrentTenantUser.require(request);
        return walletService.updateAccount(TenantRequestIds.resolveBusinessId(request), body);
    }

    @GetMapping("/ledger")
    @PreAuthorize("hasPermission(null, 'payments.gateways.read')")
    public List<KioskPayLedgerEntryResponse> ledger(
            HttpServletRequest request,
            @RequestParam(defaultValue = "20") int limit
    ) {
        CurrentTenantUser.require(request);
        return walletService.listLedger(TenantRequestIds.resolveBusinessId(request), limit);
    }

    @GetMapping("/withdrawals")
    @PreAuthorize("hasPermission(null, 'payments.gateways.read')")
    public List<KioskPayWithdrawalResponse> withdrawals(
            HttpServletRequest request,
            @RequestParam(defaultValue = "20") int limit
    ) {
        CurrentTenantUser.require(request);
        return withdrawService.list(TenantRequestIds.resolveBusinessId(request), limit);
    }

    @PostMapping("/withdrawals")
    @PreAuthorize("hasPermission(null, 'payments.gateways.write')")
    public KioskPayWithdrawalResponse withdraw(
            HttpServletRequest request,
            @Valid @RequestBody KioskPayWithdrawRequest body
    ) {
        CurrentTenantUser.require(request);
        return withdrawService.requestWithdraw(TenantRequestIds.resolveBusinessId(request), body);
    }
}
