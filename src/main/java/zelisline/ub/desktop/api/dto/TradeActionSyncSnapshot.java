package zelisline.ub.desktop.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Batch of supplier trade actions pushed till -> cloud (S2+). The cloud applies each through the same
 * service the web calls, idempotent by action id, and returns one outcome per action.
 * See DESKTOP_SUPPLIER_TRADE_SYNC_SCOPE.md.
 */
public record TradeActionSyncSnapshot(
        @Valid List<Action> actions
) {

    public record Action(
            @NotBlank String id,
            @NotBlank String kind,
            @NotBlank String targetId,
            /** JSON the cloud service needs for this kind (e.g. accepted quantities for an answer). */
            String payload,
            String actorUserId
    ) {}

    public record Ack(
            List<Outcome> outcomes
    ) {}

    public record Outcome(
            String id,
            /** applied | refused */
            String outcome,
            /** the cloud's reason when refused, shown to the person */
            String detail
    ) {}
}
