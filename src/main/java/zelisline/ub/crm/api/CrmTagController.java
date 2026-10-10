package zelisline.ub.crm.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import zelisline.ub.crm.api.dto.CrmDtos;
import zelisline.ub.crm.application.CrmTagService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

/** Inbox tag vocabulary. */
@Validated
@RestController
@RequestMapping("/api/v1/crm/tags")
@RequiredArgsConstructor
public class CrmTagController {

    private final CrmTagService tagService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'crm.inbox.read')")
    public List<CrmDtos.TagRow> list(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return tagService.list(TenantRequestIds.resolveBusinessId(request));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'crm.inbox.manage')")
    public CrmDtos.TagRow create(
            @Valid @RequestBody CrmDtos.CreateTagRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return tagService.create(
                TenantRequestIds.resolveBusinessId(request), body.name(), body.color());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(null, 'crm.inbox.manage')")
    public void delete(@PathVariable String id, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        tagService.delete(TenantRequestIds.resolveBusinessId(request), id);
    }
}
