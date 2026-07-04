package zelisline.ub.storefront.api.dto;

public record CheckoutProfileSnapshot(
        String firstName,
        String lastName,
        String email,
        String areaCode,
        String phone,
        String whatsApp,
        String county,
        String subCounty,
        String ward,
        String streetAddress,
        String deliveryNotes
) {}
