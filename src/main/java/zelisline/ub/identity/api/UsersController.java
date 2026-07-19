package zelisline.ub.identity.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
import zelisline.ub.identity.api.dto.AdminSetPasswordRequest;
import zelisline.ub.identity.api.dto.AssignRoleRequest;
import zelisline.ub.identity.api.dto.CreateUserRequest;
import zelisline.ub.identity.api.dto.SetUserItemTypesRequest;
import zelisline.ub.identity.api.dto.UpdateUserRequest;
import zelisline.ub.identity.api.dto.UserResponse;
import zelisline.ub.identity.application.IdentityService;
import zelisline.ub.identity.application.UserInvitationService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UsersController {

    private final IdentityService identityService;
    private final UserInvitationService userInvitationService;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'users.list')")
    public Page<UserResponse> listUsers(
            Pageable pageable,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String roleId,
            @RequestParam(required = false) String branchId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return identityService.listUsers(
                TenantRequestIds.resolveBusinessId(request),
                pageable,
                status,
                roleId,
                branchId
        );
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'users.create')")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse createUser(
            @Valid @RequestBody CreateUserRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String actorId = CurrentTenantUser.auditActorId(request);
        UserResponse created = identityService.createUser(businessId, body);
        if (Boolean.TRUE.equals(body.sendInvite())) {
            userInvitationService.sendInvite(request, businessId, created.id());
        }
        auditEventPublisher.publish(auditEventBuilder.builder(AuditEventCategory.STAFF, AuditEventTypes.USER_CREATED, AuditEventSeverity.INFO)
                .businessId(businessId)
                .branchId(created.branchId())
                .actor(actorId, AuditEventActorType.USER)
                .target("user", created.id())
                .targetLabel(created.email())
                .ipAddress(clientIp(request))
                .userAgent(request.getHeader("User-Agent"))
                .source("web_admin")
                .newState(map("roleId", created.role().id(), "status", created.status()))
                .build());
        return created;
    }

    @GetMapping("/{userId}")
    @PreAuthorize("hasPermission(null, 'users.list')")
    public UserResponse getUser(@PathVariable String userId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return identityService.getUser(TenantRequestIds.resolveBusinessId(request), userId);
    }

    @PatchMapping("/{userId}")
    @PreAuthorize("hasPermission(null, 'users.update')")
    public UserResponse updateUser(
            @PathVariable String userId,
            @Valid @RequestBody UpdateUserRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String actorId = CurrentTenantUser.auditActorId(request);
        UserResponse before = identityService.getUser(businessId, userId);
        UserResponse updated = identityService.updateUser(businessId, userId, body);
        auditEventPublisher.publish(auditEventBuilder.builder(AuditEventCategory.STAFF, AuditEventTypes.USER_UPDATED, AuditEventSeverity.INFO)
                .businessId(businessId)
                .branchId(updated.branchId())
                .actor(actorId, AuditEventActorType.USER)
                .target("user", updated.id())
                .targetLabel(updated.email())
                .ipAddress(clientIp(request))
                .userAgent(request.getHeader("User-Agent"))
                .source("web_admin")
                .oldState(map("name", before.name(), "phone", before.phone(), "branchId", before.branchId(), "status", before.status()))
                .newState(map("name", updated.name(), "phone", updated.phone(), "branchId", updated.branchId(), "status", updated.status()))
                .build());
        return updated;
    }

    @PostMapping("/{userId}/password")
    @PreAuthorize("hasPermission(null, 'users.update')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setPassword(
            @PathVariable String userId,
            @Valid @RequestBody AdminSetPasswordRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String actorId = CurrentTenantUser.auditActorId(request);
        UserResponse updated = identityService.setUserPassword(businessId, userId, body.newPassword());
        auditEventPublisher.publish(auditEventBuilder.builder(AuditEventCategory.SECURITY, AuditEventTypes.PASSWORD_CHANGED, AuditEventSeverity.INFO)
                .businessId(businessId)
                .branchId(updated.branchId())
                .actor(actorId, AuditEventActorType.USER)
                .target("user", updated.id())
                .targetLabel(updated.email())
                .ipAddress(clientIp(request))
                .userAgent(request.getHeader("User-Agent"))
                .source("web_admin")
                .build());
    }

    @PostMapping("/{userId}/deactivate")
    @PreAuthorize("hasPermission(null, 'users.deactivate')")
    public UserResponse deactivateUser(@PathVariable String userId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String actorId = CurrentTenantUser.auditActorId(request);
        UserResponse before = identityService.getUser(businessId, userId);
        UserResponse deactivated = identityService.deactivateUser(businessId, userId);
        auditEventPublisher.publish(auditEventBuilder.builder(AuditEventCategory.STAFF, AuditEventTypes.USER_DEACTIVATED, AuditEventSeverity.INFO)
                .businessId(businessId)
                .branchId(deactivated.branchId())
                .actor(actorId, AuditEventActorType.USER)
                .target("user", deactivated.id())
                .targetLabel(deactivated.email())
                .ipAddress(clientIp(request))
                .userAgent(request.getHeader("User-Agent"))
                .source("web_admin")
                .oldState(map("status", before.status()))
                .newState(map("status", deactivated.status()))
                .build());
        return deactivated;
    }

    @PostMapping("/{userId}/role")
    @PreAuthorize("hasPermission(null, 'users.assign_role')")
    public UserResponse assignRole(
            @PathVariable String userId,
            @Valid @RequestBody AssignRoleRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String actorId = CurrentTenantUser.auditActorId(request);
        UserResponse before = identityService.getUser(businessId, userId);
        UserResponse updated = identityService.assignRole(businessId, userId, body);
        auditEventPublisher.publish(auditEventBuilder.builder(AuditEventCategory.SECURITY, AuditEventTypes.USER_ROLE_ASSIGNED, AuditEventSeverity.INFO)
                .businessId(businessId)
                .branchId(updated.branchId())
                .actor(actorId, AuditEventActorType.USER)
                .target("user", updated.id())
                .targetLabel(updated.email())
                .ipAddress(clientIp(request))
                .userAgent(request.getHeader("User-Agent"))
                .source("web_admin")
                .oldState(map("roleId", before.role().id(), "roleKey", before.role().key()))
                .newState(map("roleId", updated.role().id(), "roleKey", updated.role().key()))
                .build());
        return updated;
    }

    @GetMapping("/{userId}/item-types")
    @PreAuthorize("hasPermission(null, 'users.list')")
    public Map<String, List<String>> getUserItemTypes(
            @PathVariable String userId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        List<String> ids = identityService.listItemTypeIdsForUser(
                TenantRequestIds.resolveBusinessId(request), userId);
        return Map.of("itemTypeIds", ids);
    }

    @PutMapping("/{userId}/item-types")
    @PreAuthorize("hasPermission(null, 'users.update')")
    public Map<String, List<String>> setUserItemTypes(
            @PathVariable String userId,
            @Valid @RequestBody SetUserItemTypesRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String actorId = CurrentTenantUser.auditActorId(request);
        List<String> before = identityService.listItemTypeIdsForUser(businessId, userId);
        List<String> ids = identityService.setItemTypeIdsForUser(businessId, userId, body.itemTypeIds());
        auditEventPublisher.publish(auditEventBuilder.builder(AuditEventCategory.STAFF, AuditEventTypes.USER_UPDATED, AuditEventSeverity.INFO)
                .businessId(businessId)
                .actor(actorId, AuditEventActorType.USER)
                .target("user", userId)
                .ipAddress(clientIp(request))
                .userAgent(request.getHeader("User-Agent"))
                .source("web_admin")
                .oldState(map("itemTypeIds", before))
                .newState(map("itemTypeIds", ids))
                .build());
        return Map.of("itemTypeIds", ids);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
