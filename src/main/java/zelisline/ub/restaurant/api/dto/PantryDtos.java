package zelisline.ub.restaurant.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The pantry (ADR 0026): a branch's ingredients restated as the portions they make. */
public final class PantryDtos {

    private PantryDtos() {
    }

    /**
     * @param canMake      portions the usable stock allows, if only this dish were made
     * @param soldPerDay   average sold a day over the last fourteen days
     * @param daysOfCover  how long {@code canMake} lasts at that rate; null when none sold
     */
    public record PantryDish(
            String menuItemId, String name, long canMake, String limitingIngredient, long mustUseToday,
            BigDecimal soldPerDay, BigDecimal daysOfCover) {
    }

    /**
     * @param usable          unexpired stock at the branch, in the ingredient's own unit
     * @param dishes          names of the dishes that use it
     * @param servingsDish    the dish its stock is stated in: the one of those that sells most
     * @param perServing      what one portion of that dish takes of it
     * @param servings        portions of that dish this stock alone would make
     * @param expiringSoon    the part of {@code usable} whose batch expires within three days
     * @param firstExpiry     the first day any of it expires; null when none does
     * @param lastBoughtAt    the newest delivery from a supplier; null when none in the last year
     * @param lastUnitCost    what that delivery cost per unit; else the product's buying price
     */
    public record PantryIngredient(
            String itemId, String name, String unit, BigDecimal usable,
            /** Stock still on the shelf whose batch has already expired: it makes nothing, and can be written off. */
            BigDecimal expired,
            List<String> dishes,
            String servingsDish, BigDecimal perServing, long servings, BigDecimal expiringSoon,
            LocalDate firstExpiry, Instant lastBoughtAt, String lastSupplier, BigDecimal lastUnitCost) {
    }

    /** Stock written off in the last seven days, and the portions it would have made. */
    public record PantryLoss(
            String itemId, String name, String unit, String reason, BigDecimal quantity, BigDecimal cost,
            String servingsDish, long servingsLost) {
    }

    public record PantryResponse(
            String branchId, Instant asOf, List<PantryDish> dishes, List<PantryIngredient> ingredients,
            List<PantryLoss> losses) {
    }

    public record PlanPortion(@NotBlank String menuItemId, @NotNull @Min(0) @Max(100000) Integer portions) {
    }

    public record PlanRequest(
            @NotBlank @Size(max = 36) String branchId,
            @NotNull @Size(min = 1, max = 300) @Valid List<PlanPortion> portions) {
    }

    /**
     * @param needed        what the plan takes of the ingredient
     * @param shortfall     how much more must be bought; zero when there is enough
     * @param estimatedCost the shortfall at the last price paid; null when no price is known
     */
    public record PlanLine(
            String itemId, String name, String unit, BigDecimal needed, BigDecimal usable, BigDecimal shortfall,
            BigDecimal unitCost, BigDecimal estimatedCost, String lastSupplier) {
    }

    /** @param costKnown false when some shortfall has no known price, so the total is a floor */
    public record PlanResponse(List<PlanLine> lines, BigDecimal estimatedTotal, boolean costKnown) {
    }

    /** {@code reason}: SPOILAGE, BREAKAGE, EXPIRED or OTHER. */
    public record LossRequest(
            @NotBlank @Size(max = 36) String branchId,
            @NotBlank String itemId,
            @NotNull @DecimalMin("0.0001") BigDecimal quantity,
            @NotBlank String reason,
            @Size(max = 200) String note) {
    }

    /** @param cost what inventory wrote off, at the cost of the batches it came from */
    public record LossResponse(
            String itemId, String name, BigDecimal quantity, BigDecimal cost, String servingsDish, long servingsLost) {
    }
}
