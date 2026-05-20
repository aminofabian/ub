package zelisline.ub.credits.api.dto;

public record CreditSaleReminderTestResponse(
        boolean remindersEnabled,
        boolean rapidApiConfigured,
        boolean metaWhatsAppConfigured,
        boolean smsConfigured,
        boolean whatsAppLookupSkipped,
        boolean onWhatsApp,
        String lookupDetail,
        String channel,
        String outcome,
        String detail,
        String messagePreview
) {
}
