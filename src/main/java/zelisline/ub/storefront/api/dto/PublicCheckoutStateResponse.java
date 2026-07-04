package zelisline.ub.storefront.api.dto;

public record PublicCheckoutStateResponse(
        boolean authenticated,
        int currentStep,
        String detailsSubStep,
        CheckoutStepCompletion completed,
        CheckoutProfileSnapshot profile,
        String guestKey
) {}
