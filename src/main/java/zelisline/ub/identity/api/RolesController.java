package zelisline.ub.identity.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.identity.api.dto.CreateRoleRequest;
import zelisline.ub.identity.api.dto.RoleResponse;
import zelisline.ub.identity.api.dto.UpdateRoleRequest;
import zelisline.ub.identity.application.IdentityService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/roles")
@RequiredArgsConstructor
public class RolesController {

    private final IdentityService identityService;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'roles.list')")
    public List<RoleResponse> listRoles(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return identityService.listRoles(TenantRequestIds.resolveBusinessId(request));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'roles.create')")
    @ResponseStatus(HttpStatus.CREATED)
    public RoleResponse createRole(
            @Valid @RequestBody CreateRoleRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String actorId = CurrentTenantUser.auditActorId(request);
        RoleResponse created = identityService.createRole(businessId, body);
        auditEventPublisher.publish(auditEventBuilder.builder(AuditEventCategory.SECURITY, AuditEventTypes.ROLE_CREATED, AuditEventSeverity.INFO)
                .businessId(businessId)
                .actor(actorId, AuditEventActorType.USER)
                .target("role", created.id())
                .targetLabel(created.name())
                .ipAddress(clientIp(request))
                .userAgent(request.getHeader("User-Agent"))
                .source("web_admin")
                .newState(map("roleKey", created.key(), "permissions", created.permissionKeys()))
                .build());
        return created;
    }

    @PatchMapping("/{roleId}")
    @PreAuthorize("hasPermission(null, 'roles.update')")
    public RoleResponse updateRole(
            @PathVariable String roleId,
            @Valid @RequestBody UpdateRoleRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String actorId = CurrentTenantUser.auditActorId(request);
        RoleResponse updated = identityService.updateRole(businessId, roleId, body);
        auditEventPublisher.publish(auditEventBuilder.builder(AuditEventCategory.SECURITY, AuditEventTypes.ROLE_UPDATED, AuditEventSeverity.INFO)
                .businessId(businessId)
                .actor(actorId, AuditEventActorType.USER)
                .target("role", updated.id())
                .targetLabel(updated.name())
                .ipAddress(clientIp(request))
                .userAgent(request.getHeader("User-Agent"))
                .source("web_admin")
                .newState(map("roleKey", updated.key(), "permissions", updated.permissionKeys()))
                .build());
        return updated;
    }

    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put((String) entries[i], entries[i + 1]);
        }
        return map;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
