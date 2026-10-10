package zelisline.ub.crm.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import zelisline.ub.crm.api.dto.CrmDtos;
import zelisline.ub.crm.application.CrmBroadcastService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

/** WhatsApp broadcasts (M4): compose and follow a fan-out. */
@Validated
@RestController
@RequestMapping("/api/v1/crm/broadcasts")
@RequiredArgsConstructor
public class CrmBroadcastController {

    private final CrmBroadcastService broadcastService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'crm.inbox.read')")
    public CrmDtos.BroadcastsPage list(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "30") int size,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return broadcastService.list(TenantRequestIds.resolveBusinessId(request), page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'crm.inbox.read')")
    public CrmDtos.BroadcastDetail get(@PathVariable String id, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return broadcastService.get(TenantRequestIds.resolveBusinessId(request), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(null, 'crm.broadcast.send')")
    public CrmDtos.BroadcastDetail create(
            @Valid @RequestBody CrmDtos.CreateBroadcastRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return broadcastService.create(TenantRequestIds.resolveBusinessId(request), body);
    }
}
