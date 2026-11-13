package zelisline.ub.credits.sms.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import zelisline.ub.credits.sms.api.dto.SmsHubDtos.AutomationsResponse;
import zelisline.ub.credits.sms.api.dto.SmsHubDtos.ContactsExportResponse;
import zelisline.ub.credits.sms.api.dto.SmsHubDtos.ContactsPageResponse;
import zelisline.ub.credits.sms.api.dto.SmsHubDtos.ImportContactsRequest;
import zelisline.ub.credits.sms.api.dto.SmsHubDtos.ImportContactsResponse;
import zelisline.ub.credits.sms.api.dto.SmsHubDtos.MessageRow;
import zelisline.ub.credits.sms.api.dto.SmsHubDtos.SummaryResponse;
import zelisline.ub.credits.sms.api.dto.SmsHubDtos.UpdateAutomationsRequest;
import zelisline.ub.credits.sms.api.dto.SmsHubDtos.UpdateConsentRequest;
import zelisline.ub.credits.sms.api.dto.SmsHubDtos.UpdateConsentResponse;
import zelisline.ub.credits.sms.application.SmsCampaignText;
import zelisline.ub.credits.sms.application.SmsHubContactImportService;
import zelisline.ub.credits.sms.application.SmsHubContactsService;
import zelisline.ub.credits.sms.application.SmsHubSettingsService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * SMS Hub read side and settings (docs/scopes/SMS_HUB_SCOPE.md). The business always
 * comes from the request's tenant, never from a parameter or body.
 */
@Validated
@RestController
@RequestMapping("/api/v1/sms-hub")
public class SmsHubController {

    private static final int MAX_PAGE_SIZE = 100;

    private final SmsHubContactsService contactsService;
    private final SmsHubSettingsService settingsService;
    private final SmsCampaignText campaignText;
    private final SmsHubContactImportService contactImportService;

    public SmsHubController(
            SmsHubContactsService contactsService,
            SmsHubSettingsService settingsService,
            SmsCampaignText campaignText,
            SmsHubContactImportService contactImportService
    ) {
        this.contactImportService = contactImportService;
        this.contactsService = contactsService;
        this.settingsService = settingsService;
        this.campaignText = campaignText;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasPermission(null, 'credits.customers.read')")
    public SummaryResponse summary(HttpServletRequest http) {
        CurrentTenantUser.require(http);
        return contactsService.summary(TenantRequestIds.resolveBusinessId(http));
    }

    @GetMapping("/contacts")
    @PreAuthorize("hasPermission(null, 'credits.customers.read')")
    public ContactsPageResponse contacts(
            @RequestParam(required = false) String reach,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            HttpServletRequest http
    ) {
        CurrentTenantUser.require(http);
        return contactsService.contacts(TenantRequestIds.resolveBusinessId(http), reach, q, page, size);
    }

    @PostMapping("/contacts/consent")
    @PreAuthorize("hasPermission(null, 'credits.customers.write')")
    public UpdateConsentResponse updateConsent(
            @Valid @RequestBody UpdateConsentRequest request,
            HttpServletRequest http
    ) {
        CurrentTenantUser.require(http);
        return new UpdateConsentResponse(
                contactsService.updateConsent(TenantRequestIds.resolveBusinessId(http), request));
    }

    /** Add numbers typed by staff or read from a file. Re-sending the same rows adds nothing twice. */
    @PostMapping("/contacts/import")
    @PreAuthorize("hasPermission(null, 'credits.customers.write')")
    public ImportContactsResponse importContacts(
            @Valid @RequestBody ImportContactsRequest request,
            HttpServletRequest http
    ) {
        String actorId = CurrentTenantUser.auditActorId(http);
        return contactImportService.importContacts(TenantRequestIds.resolveBusinessId(http), actorId, request);
    }

    /** The whole filtered contact list for a file download; the client writes the file. */
    @GetMapping("/contacts/export")
    @PreAuthorize("hasPermission(null, 'credits.customers.read')")
    public ContactsExportResponse exportContacts(
            @RequestParam(required = false) String reach,
            @RequestParam(required = false) String q,
            HttpServletRequest http
    ) {
        CurrentTenantUser.require(http);
        return new ContactsExportResponse(
                contactsService.export(TenantRequestIds.resolveBusinessId(http), reach, q));
    }

    @GetMapping("/messages")
    @PreAuthorize("hasPermission(null, 'credits.customers.read')")
    public Page<MessageRow> messages(
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) String campaignId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            HttpServletRequest http
    ) {
        CurrentTenantUser.require(http);
        return contactsService.messages(
                TenantRequestIds.resolveBusinessId(http),
                customerId,
                campaignId,
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(MAX_PAGE_SIZE, size))));
    }

    @GetMapping("/automations")
    @PreAuthorize("hasPermission(null, 'credits.customers.read')")
    public AutomationsResponse automations(HttpServletRequest http) {
        CurrentTenantUser.require(http);
        return settingsService.view(TenantRequestIds.resolveBusinessId(http), campaignText.optOutFooter());
    }

    @PutMapping("/automations")
    @PreAuthorize("hasPermission(null, 'credits.customers.write')")
    public AutomationsResponse updateAutomations(
            @Valid @RequestBody UpdateAutomationsRequest request,
            HttpServletRequest http
    ) {
        CurrentTenantUser.require(http);
        return settingsService.update(
                TenantRequestIds.resolveBusinessId(http), request, campaignText.optOutFooter());
    }
}
