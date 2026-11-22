package zelisline.ub.marketplace.api.dto;

import java.util.List;

/** What happened for each supplier portal user when the shop invited its portal team. */
public record ShopTeamInviteResponse(
        List<Row> rows
) {

    /** {@code outcome}: invited, already_in_shop, no_email or failed (with the reason). */
    public record Row(String name, String email, String outcome, String reason) {
    }
}
