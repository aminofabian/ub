package zelisline.ub.storefront.api.dto;

public record PublicLeadCaptureResponse(
        boolean saved,
        String guestKey,
        String deliveryArea,
        String streetAddress
) {
}
