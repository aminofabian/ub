package zelisline.ub.credits.api.dto;

import java.util.List;

/**
 * Explains whether business-initiated ("cold") WhatsApp sends can work right now.
 */
public record WhatsAppDiagnosticsResponse(
        boolean metaWhatsAppConfigured,
        String phoneNumberId,
        String graphVersion,
        String tokenSource,
        String tokenFingerprint,
        String displayPhoneNumber,
        String verifiedName,
        String qualityRating,
        String messagingLimitTier,
        String nameStatus,
        String phoneError,
        String wabaId,
        String wabaError,
        List<TemplateStatus> templates,
        String templatesError,
        boolean coldSendReady,
        List<String> findings
) {
    public record TemplateStatus(
            String name,
            String language,
            String status,
            String category,
            String rejectedReason,
            String qualityScore
    ) {
    }
}
