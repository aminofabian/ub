package zelisline.ub.marketplace.api.dto;

public record SupplierPortalNotificationPrefsResponse(
        boolean notifyPoInApp,
        boolean notifyPoSms,
        boolean notifyPaymentInApp,
        boolean notifyPaymentSms,
        boolean notifyDeliveryInApp
) {
}
