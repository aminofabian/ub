package zelisline.ub.storefront.api.dto;

public record CheckoutStepCompletion(
        boolean contact,
        boolean delivery
) {}
