package zelisline.ub.tenancy.application;

import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.application.RequestPermissionService;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.repository.RoleRepository;
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
    private final RoleRepository roleRepository;

    /** Role keys that are locked to their assigned branch — cannot switch or see other branches. */
    private static final Set<String> BRANCH_LOCKED_ROLE_KEYS = Set.of(
            "stock_manager", "cashier", "grocery_clerk");

    /** Role key for grocery counter staff (read scoped to invoices they themselves created). */
    private static final String GROCERY_CLERK_ROLE_KEY = "grocery_clerk";

    /**
     * Returns {@code true} when the given role is locked to its assigned branch.
     */
    public boolean isBranchLockedRole(String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return false;
        }
        Role role = roleRepository.findByIdAndDeletedAtIsNull(roleId).orElse(null);
        if (role == null || role.getRoleKey() == null) {
            return false;
        }
        return BRANCH_LOCKED_ROLE_KEYS.contains(role.getRoleKey().trim().toLowerCase());
    }

    /**
     * Returns {@code true} when the given role is {@code grocery_clerk}. Grocery
     * clerks generate invoices but can only see / cancel the invoices they
     * themselves created — the service layer narrows reads to their own rows.
     */
    public boolean isGroceryClerkRole(String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return false;
        }
        Role role = roleRepository.findByIdAndDeletedAtIsNull(roleId).orElse(null);
        if (role == null || role.getRoleKey() == null) {
            return false;
        }
        return GROCERY_CLERK_ROLE_KEY.equalsIgnoreCase(role.getRoleKey().trim());
    }

    /**
     * Resolve the effective branch for a business, given an optional
     * session branch and the user's role. When multi-branch is OFF and
     * no session branch is provided, returns the business's default branch.
     *
     * <p>Stock managers and cashiers are locked to their assigned branch.
     */
    public String resolveEffectiveBranch(String businessId, String sessionBranchId, String roleId) {
        // Stock managers and cashiers are locked to their assigned branch.
        if (isBranchLockedRole(roleId)) {
            if (sessionBranchId != null && !sessionBranchId.isBlank()) {
                return sessionBranchId;
            }
            String defaultBranch = resolveDefaultBranch(businessId);
            if (defaultBranch == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Branch is required. Contact your administrator to assign you a branch."
                );
            }
            return defaultBranch;
        }

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
     * @deprecated Use {@link #resolveEffectiveBranch(String, String, String)} with roleId instead.
     */
    @Deprecated
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
     *
     * <p>Stock managers and cashiers are locked to their assigned branch;
     * explicit branch requests are ignored and their session branch is used.</p>
     */
    public String resolveBranchForReport(
            String businessId,
            String roleId,
            String sessionBranchId,
            String explicitBranchId
    ) {
        // Stock managers and cashiers are locked to their assigned branch.
        if (isBranchLockedRole(roleId)) {
            if (sessionBranchId != null && !sessionBranchId.isBlank()) {
                return sessionBranchId;
            }
            String defaultBranch = resolveDefaultBranch(businessId);
            if (defaultBranch == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Branch is required. Contact your administrator to assign you a branch."
                );
            }
            return defaultBranch;
        }

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
     * Validates that the requested branch is allowed for the user's role.
     *
     * <p>For stock managers and cashiers, the requested branch MUST match
     * their assigned branch (from the JWT). Returns the validated branch ID
     * or throws {@link ResponseStatusException#FORBIDDEN}.</p>
     *
     * <p>For all other roles, returns the requested branch as-is (no restriction).</p>
     *
     * @param roleId          the user's role ID (from JWT)
     * @param assignedBranch  the user's assigned branch (from JWT, may be null)
     * @param requestedBranch the branch the user wants to operate on
     * @return the validated branch ID (requestedBranch for unlocked roles, assignedBranch for locked)
     * @throws ResponseStatusException with FORBIDDEN if a locked role tries a different branch
     */
    public String requireBranchForLockedRole(
            String roleId,
            String assignedBranch,
            String requestedBranch
    ) {
        if (!isBranchLockedRole(roleId)) {
            return requestedBranch;
        }

        // Locked role: must operate on their assigned branch only.
        String assigned = assignedBranch != null ? assignedBranch.trim() : null;
        if (assigned == null || assigned.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Your account is not assigned to a branch. Contact your administrator."
            );
        }

        String requested = requestedBranch != null ? requestedBranch.trim() : null;
        if (!assigned.equals(requested)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "You can only operate on your assigned branch. Branch switching is disabled for your role."
            );
        }

        return assigned;
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
