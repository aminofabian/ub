package zelisline.ub.platform.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A request could not run because the shop has not finished a setup step (a branch, an open
 * shift, a registered till).
 *
 * <p>It extends {@link ResponseStatusException} on purpose: the HTTP status is what the call
 * sites returned before this type existed and the reason text is kept wherever it still told
 * the truth, so older clients and any code that catches {@code ResponseStatusException} keep
 * working. {@link GlobalExceptionHandler} adds the machine-readable {@code requirement} (and
 * optional {@code hint}) properties that let the UI open a dialog that fixes the gap instead
 * of showing a bare error.
 */
public class SetupRequiredException extends ResponseStatusException {

    /** Value of the problem+json {@code code} property for every setup-required response. */
    public static final String PROBLEM_CODE = "setup_required";

    /** Hint for {@link SetupRequirement#BRANCH}: the shop has no branch at all. */
    public static final String HINT_NONE = "none";

    /** Hint for {@link SetupRequirement#BRANCH}: branches exist but none is selected. */
    public static final String HINT_PICK = "pick";

    /** Hint for {@link SetupRequirement#BRANCH}: this user is not assigned to a branch. */
    public static final String HINT_ASSIGN = "assign";

    private final SetupRequirement requirement;
    private final String hint;

    public SetupRequiredException(HttpStatus status, SetupRequirement requirement, String reason) {
        this(status, requirement, null, reason);
    }

    public SetupRequiredException(
            HttpStatus status,
            SetupRequirement requirement,
            String hint,
            String reason
    ) {
        super(status, reason);
        this.requirement = requirement;
        this.hint = hint;
    }

    public SetupRequirement getRequirement() {
        return requirement;
    }

    /** Optional refinement of the requirement (for a branch: none, pick or assign); may be null. */
    public String getHint() {
        return hint;
    }
}
