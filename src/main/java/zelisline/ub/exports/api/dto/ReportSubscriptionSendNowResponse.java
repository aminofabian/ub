package zelisline.ub.exports.api.dto;

import java.util.List;

/** Result of an immediate "send now" of a subscription. */
public record ReportSubscriptionSendNowResponse(
        List<String> sent,
        List<String> failed
) {
}
