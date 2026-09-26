package zelisline.ub.identity.api.dto;

public record EmailLookupResponse(
        boolean registered,
        /**
         * True when the account has a linked Google identity (it may also have a
         * password). Lets the office sign-in screen point Google-only owners at
         * "Sign in with Google" instead of a bare "Incorrect email or password".
         */
        boolean usesGoogle
) {
}
