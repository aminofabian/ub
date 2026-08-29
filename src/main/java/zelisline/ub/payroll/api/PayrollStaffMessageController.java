package zelisline.ub.payroll.api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.payroll.api.dto.StaffSmsBulkSendRequest;
import zelisline.ub.payroll.api.dto.StaffSmsBulkSendResponse;
import zelisline.ub.payroll.api.dto.StaffSmsPreviewRequest;
import zelisline.ub.payroll.api.dto.StaffSmsPreviewResponse;
import zelisline.ub.payroll.api.dto.StaffSmsSendRequest;
import zelisline.ub.payroll.api.dto.StaffSmsSendResponse;
import zelisline.ub.payroll.api.dto.StaffSmsTemplateResponse;
import zelisline.ub.payroll.application.StaffSmsMessageService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/payroll/staff-messages")
@RequiredArgsConstructor
public class PayrollStaffMessageController {

    private final StaffSmsMessageService staffSmsMessageService;

    @GetMapping("/templates")
    @PreAuthorize("hasPermission(null, 'payroll.view')")
    public List<StaffSmsTemplateResponse> listTemplates(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return staffSmsMessageService.listTemplates();
    }

    @PostMapping("/{userId}/preview")
    @PreAuthorize("hasPermission(null, 'payroll.manage')")
    public StaffSmsPreviewResponse preview(
            @PathVariable String userId,
            @Valid @RequestBody StaffSmsPreviewRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return staffSmsMessageService.preview(
                TenantRequestIds.resolveBusinessId(request),
                userId,
                body.templateKey(),
                body.bodyOverride()
        );
    }

    @PostMapping("/{userId}/send")
    @PreAuthorize("hasPermission(null, 'payroll.manage')")
    public StaffSmsSendResponse send(
            @PathVariable String userId,
            @Valid @RequestBody StaffSmsSendRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return staffSmsMessageService.send(
                TenantRequestIds.resolveBusinessId(request),
                userId,
                body
        );
    }

    @PostMapping("/bulk-send")
    @PreAuthorize("hasPermission(null, 'payroll.manage')")
    public StaffSmsBulkSendResponse bulkSend(
            @Valid @RequestBody StaffSmsBulkSendRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return staffSmsMessageService.sendBulk(
                TenantRequestIds.resolveBusinessId(request),
                body
        );
    }
}
