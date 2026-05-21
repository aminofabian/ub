package zelisline.ub.notifications.api.dto;

public record PushConfigResponse(
        boolean enabled,
        String publicKey,
        boolean fcmEnabled
) {
}
