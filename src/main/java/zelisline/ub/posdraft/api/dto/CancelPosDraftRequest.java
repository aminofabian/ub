package zelisline.ub.posdraft.api.dto;

public record CancelPosDraftRequest(
        String reason,
        /** True when the till's "Clear all" cleared this cart along with the others. */
        Boolean clearAll
) {
    public CancelPosDraftRequest(String reason) {
        this(reason, null);
    }
}
