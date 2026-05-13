package zelisline.ub.tenancy.application;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.application.RequestPermissionService;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.repository.BranchRepository;

/**
 * Phase 9 Slice 1-2: Resolves the effective branch for a request based on the
 * {@code multi_branch} feature flag and {@code reports.branch.all} permission.
 *
 * <p>When {@code multi_branch} is ON, the branch comes from the session/JWT
 * and is validated against the user's allowed branches. When OFF (single-branch
 * mode), the branch is auto-resolved to the business's default (first/only)
 * branch, and the branch switcher UI is hidden.</p>
 */
@Service
@RequiredArgsConstructor
public class BranchResolutionService {

    private final BranchRepository branchRepository;
    private final FeatureFlagService featureFlagService;
    private final RequestPermissionService requestPermissionService;

    /**
     * Resolve the effective branch for a business, given an optional
     * session branch. When multi-branch is OFF and no session branch is
     * provided, returns the business's default branch.
     */
    public String resolveEffectiveBranch(String businessId, String sessionBranchId) {
        if (featureFlagService.isMultiBranchEnabled(businessId)) {
            if (sessionBranchId != null && !sessionBranchId.isBlank()) {
                branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(sessionBranchId, businessId)
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "Branch not found or not in this business"));
                return sessionBranchId;
            }
            return null;
        }
        return resolveDefaultBranch(businessId);
    }

    /**
     * Phase 9 Slice 2: Resolve the branch for a report/analytics query.
     * Users with {@code reports.branch.all} may pass null for HQ cross-branch
     * rollup. Others are scoped to explicit, session, or default branch.
     */
    public String resolveBranchForReport(
            String businessId,
            String roleId,
            String sessionBranchId,
            String explicitBranchId
    ) {
        boolean canSeeAll = requestPermissionService.hasPermission(roleId, "reports.branch.all");

        if (canSeeAll) {
            if (explicitBranchId != null && !explicitBranchId.isBlank()) {
                return explicitBranchId.trim();
            }
            return null; // HQ: all branches
        }

        String resolved = explicitBranchId != null && !explicitBranchId.isBlank()
                ? explicitBranchId.trim()
                : sessionBranchId;

        if (resolved == null || resolved.isBlank()) {
            resolved = resolveDefaultBranch(businessId);
        }

        if (resolved == null || resolved.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Branch is required. Select a branch or contact your administrator."
            );
        }
        return resolved;
    }

    /**
     * Find the default branch for a business - the first non-deleted branch.
     * Returns null when the business has no branches yet.
     */
    public String resolveDefaultBranch(String businessId) {
        List<Branch> branches = branchRepository
                .findByBusinessIdAndDeletedAtIsNullOrderByNameAsc(businessId);
        if (branches.isEmpty()) {
            return null;
        }
        return branches.get(0).getId();
    }
}
