package zelisline.ub.platform.web;

/**
 * Something a shop has to set up before an action can run.
 *
 * <p>The {@link #code()} is part of the API contract: the web app and the desktop till read it
 * from the problem+json {@code requirement} property to open the matching "set this up first"
 * dialog, so it must stay stable even if the human-readable message changes.
 */
public enum SetupRequirement {

    /** The shop has no branch (location) yet, or the user is not on one. */
    BRANCH("branch"),

    /** The till has no open cash-drawer shift and the shop uses shifts. */
    SHIFT("shift"),

    /** This computer is not a registered till for a branch that only trusts registered tills. */
    TILL("till");

    private final String code;

    SetupRequirement(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
