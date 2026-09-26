package zelisline.ub.identity.api.dto;

public record MeOAuthLinksResponse(
        /** True when this account has a linked Google identity. */
        boolean googleLinked
) {
}
