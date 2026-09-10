package zelisline.ub.platform.api.dto;

/** Booleans: {@code null} = leave unchanged. */
public record UpdatePlatformAuthSettingsRequest(
        Boolean emailVerificationRequired
) {}
