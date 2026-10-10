package zelisline.ub.crm.api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

import zelisline.ub.crm.api.dto.CrmDtos;
import zelisline.ub.crm.application.InboxAgentDirectory;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * Hand-off agent directory for the WhatsApp inbox: active users who can work the inbox. Scoped by
 * permission ({@code crm.inbox.read}) rather than the full staff directory, so a CRM manager
 * without {@code users.list} can still pick a hand-off agent.
 */
@Validated
@RestController
@RequestMapping("/api/v1/crm/agents")
@RequiredArgsConstructor
public class CrmAgentController {

    private final InboxAgentDirectory agentDirectory;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'crm.inbox.read')")
    public List<CrmDtos.AgentRow> list(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return agentDirectory.listInboxAgents(TenantRequestIds.resolveBusinessId(request)).stream()
                .map(agent -> new CrmDtos.AgentRow(agent.id(), agent.name()))
                .toList();
    }
}
