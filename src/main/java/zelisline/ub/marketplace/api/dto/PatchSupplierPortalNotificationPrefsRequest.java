package zelisline.ub.marketplace.api.dto;

public record PatchSupplierPortalNotificationPrefsRequest(
        Boolean notifyPoInApp,
        Boolean notifyPoSms,
        Boolean notifyPaymentInApp,
        Boolean notifyPaymentSms,
        Boolean notifyDeliveryInApp
) {
}
