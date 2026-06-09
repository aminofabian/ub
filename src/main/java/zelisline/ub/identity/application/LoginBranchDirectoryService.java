package zelisline.ub.identity.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.api.dto.PublicBranchResponse;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.repository.BranchRepository;

/**
 * Powers the branch picker on the PIN login screen. Returns the active branches
 * for the request's tenant (resolved from Host / {@code X-Tenant-Id}) as bare
 * id + name pairs, so a cashier can choose a branch instead of typing its UUID.
 *
 * <p>Reachable without authentication (see {@code PublicAuthEndpoints}); when the
 * tenant cannot be resolved (e.g. the platform apex) it returns an empty list so
 * the UI can fall back to manual entry rather than erroring.
 */
@Service
@RequiredArgsConstructor
public class LoginBranchDirectoryService {

    private final BranchRepository branchRepository;

    @Transactional(readOnly = true)
    public List<PublicBranchResponse> listForTenant(HttpServletRequest http) {
        String businessId;
        try {
            businessId = TenantRequestIds.resolveBusinessId(http);
        } catch (ResponseStatusException ex) {
            return List.of();
        }
        return branchRepository.findByBusinessIdAndDeletedAtIsNullOrderByNameAsc(businessId).stream()
                .filter(Branch::isActive)
                .map(branch -> new PublicBranchResponse(branch.getId(), branch.getName()))
                .toList();
    }
}
