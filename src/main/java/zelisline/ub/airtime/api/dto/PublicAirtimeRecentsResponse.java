package zelisline.ub.airtime.api.dto;

import java.math.BigDecimal;
import java.util.List;

/** Numbers this tab has successfully used for airtime, newest first. */
public record PublicAirtimeRecentsResponse(
        List<String> recipients,
        List<String> payers,
        String lastRecipient,
        String lastPayer,
        BigDecimal lastAmount
) {
    public static PublicAirtimeRecentsResponse empty() {
        return new PublicAirtimeRecentsResponse(List.of(), List.of(), null, null, null);
    }
}
