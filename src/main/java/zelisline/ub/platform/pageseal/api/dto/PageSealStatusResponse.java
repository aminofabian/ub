package zelisline.ub.platform.pageseal.api.dto;

public record PageSealStatusResponse(
        boolean sealed,
        String scope,
        String subjectKey,
        String displayName,
        String phoneHint,
        boolean unlockValid
) {
}
