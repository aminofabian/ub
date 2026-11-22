package zelisline.ub.wholesale.api.dto;

import java.util.List;

/**
 * What a buyer may read about a supplier. {@code lines} holds only true lines; {@code orders} and {@code buyers}
 * are the sample they rest on, from verified buyers, so a buyer can judge how much to trust them.
 */
public record BuyerLinesResponse(
        boolean enabled,
        int orders,
        int buyers,
        List<Line> lines
) {

    public record Line(String key, String label, int orders, int buyers) {
    }

    public static BuyerLinesResponse off() {
        return new BuyerLinesResponse(false, 0, 0, List.of());
    }
}
