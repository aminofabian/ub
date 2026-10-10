package zelisline.ub.crm.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import zelisline.ub.ai.application.KnowledgeBaseService;
import zelisline.ub.crm.api.dto.CrmDtos;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

/** Per-business knowledge base: documents that ground the AI reply / auto-reply. */
@Validated
@RestController
@RequestMapping("/api/v1/crm/knowledge")
@RequiredArgsConstructor
public class CrmKnowledgeController {

    private final KnowledgeBaseService knowledgeBaseService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'crm.inbox.read')")
    public List<CrmDtos.KnowledgeDocRow> list(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return knowledgeBaseService.listDocuments(TenantRequestIds.resolveBusinessId(request)).stream()
                .map(CrmKnowledgeController::toRow)
                .toList();
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'crm.inbox.manage')")
    public CrmDtos.KnowledgeDocRow create(
            @Valid @RequestBody CrmDtos.CreateKnowledgeDocRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        KnowledgeBaseService.DocumentRow row = knowledgeBaseService.createDocument(
                TenantRequestIds.resolveBusinessId(request), body.title(), body.content());
        return toRow(row);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'crm.inbox.manage')")
    public CrmDtos.KnowledgeDocRow update(
            @PathVariable String id,
            @Valid @RequestBody CrmDtos.CreateKnowledgeDocRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        KnowledgeBaseService.DocumentRow row = knowledgeBaseService.updateDocument(
                TenantRequestIds.resolveBusinessId(request), id, body.title(), body.content());
        return toRow(row);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(null, 'crm.inbox.manage')")
    public void delete(@PathVariable String id, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        knowledgeBaseService.deleteDocument(TenantRequestIds.resolveBusinessId(request), id);
    }

    private static CrmDtos.KnowledgeDocRow toRow(KnowledgeBaseService.DocumentRow row) {
        return new CrmDtos.KnowledgeDocRow(row.id(), row.title(), row.content(), row.createdAt());
    }
}
