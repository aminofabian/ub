package zelisline.ub.credits.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.api.dto.CreditSaleReminderSettingsResponse;
import zelisline.ub.credits.api.dto.CreditSaleReminderTestRequest;
import zelisline.ub.credits.api.dto.CreditSaleReminderTestResponse;
import zelisline.ub.credits.api.dto.SmsTestSendRequest;
import zelisline.ub.credits.api.dto.UpdateCreditSaleReminderSettingsRequest;
import zelisline.ub.credits.api.dto.WhatsAppTestSendRequest;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.messaging.application.CreditSaleReminderService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/credits/sale-reminder-settings")
@RequiredArgsConstructor
public class CreditSaleReminderSettingsController {

    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final CreditSaleReminderService creditSaleReminderService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'credits.customers.read')")
    public CreditSaleReminderSettingsResponse get(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return messagingSettingsService.getForAdmin(TenantRequestIds.resolveBusinessId(request));
    }

    @PutMapping
    @PreAuthorize("hasPermission(null, 'credits.settings.write')")
    public CreditSaleReminderSettingsResponse put(
            @Valid @RequestBody UpdateCreditSaleReminderSettingsRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return messagingSettingsService.update(TenantRequestIds.resolveBusinessId(request), body);
    }

    @PostMapping("/test")
    @PreAuthorize("hasPermission(null, 'credits.settings.write')")
    public CreditSaleReminderTestResponse test(
            @Valid @RequestBody CreditSaleReminderTestRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return creditSaleReminderService.sendTest(
                TenantRequestIds.resolveBusinessId(request),
                body.phone());
    }

    @PostMapping("/test-whatsapp")
    @PreAuthorize("hasPermission(null, 'credits.settings.write')")
    public CreditSaleReminderTestResponse testWhatsApp(
            @Valid @RequestBody WhatsAppTestSendRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return creditSaleReminderService.sendWhatsAppTest(
                TenantRequestIds.resolveBusinessId(request),
                body.phone(),
                body.message());
    }

    @PostMapping("/test-sms")
    @PreAuthorize("hasPermission(null, 'credits.settings.write')")
    public CreditSaleReminderTestResponse testSms(
            @Valid @RequestBody SmsTestSendRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return creditSaleReminderService.sendSmsTest(
                TenantRequestIds.resolveBusinessId(request),
                body.phone(),
                body.message());
    }
}
