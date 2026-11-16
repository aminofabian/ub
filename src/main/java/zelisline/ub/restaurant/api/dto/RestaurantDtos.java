package zelisline.ub.restaurant.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request and response shapes of the restaurant menu, kitchen and order endpoints. */
public final class RestaurantDtos {

    private RestaurantDtos() {
    }

    // ---------------------------------------------------------------- menu (read)

    public record StationResponse(String id, String name, int sortOrder, boolean active) {
    }

    public record CategoryResponse(String id, String name, String stationId, int sortOrder, boolean active,
            /** A named-menu serving window, minutes after midnight Nairobi time; null means all day. */
            Integer serveFromMinute, Integer serveToMinute) {
    }

    public record OptionResponse(
            String id, String name, BigDecimal priceDelta, boolean preselected, boolean available, int sortOrder) {
    }

    public record ModifierGroupResponse(
            String id, String name, int minSelect, int maxSelect, int sortOrder, List<OptionResponse> options) {
    }

    /**
     * @param price for a dish, its price before modifiers; for a product, its shelf price at the
     *              branch asked for (null when no branch was given or the product has no price)
     * @param sellable false when a product behind the menu item was deleted or can no longer be sold
     */
    public record MenuItemResponse(
            String id, String categoryId, String name, String kind, String itemId, BigDecimal price,
            String taxRateId, String stationId, boolean available, boolean active, boolean sellable,
            int sortOrder, List<String> modifierGroupIds,
            /** A dish's ordinary price; {@code price} is what it costs right now, which differs in happy hour. */
            BigDecimal regularPrice,
            /** True while happy hour is running for this dish. */
            boolean happyNow,
            /** False outside the dish's serving hours; it cannot be ordered then. */
            boolean servedNow,
            Integer serveFromMinute, Integer serveToMinute, BigDecimal happyPrice, Integer happyFromMinute,
            Integer happyToMinute, String happyDays,
            /** A line or two for the customer; null when none was written. */
            String description,
            /** Where the picture is served from; null when the item has none. */
            String imageUrl) {
    }

    public record MenuResponse(
            List<CategoryResponse> categories, List<MenuItemResponse> items,
            List<ModifierGroupResponse> modifierGroups, List<StationResponse> stations) {
    }

    // ---------------------------------------------------------------- menu (write)

    public record CategoryRequest(
            @NotBlank @Size(max = 120) String name, String stationId, Integer sortOrder, Boolean active,
            /** A named-menu serving window (Breakfast, Lunch); both ends or neither. Minutes 0–1439. */
            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(1439) Integer serveFromMinute,
            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(1439) Integer serveToMinute,
            /** True clears the serving window, leaving the heading served all day. */
            Boolean clearServingHours) {
    }

    public record MenuItemRequest(
            String categoryId,
            @Size(max = 191) String name,
            /** {@code DISH} or {@code ITEM}; fixed once the menu item exists. */
            String kind,
            String itemId,
            @DecimalMin(value = "0.01") BigDecimal price,
            String taxRateId,
            String stationId,
            Integer sortOrder,
            Boolean active,
            @Size(max = 20) List<String> modifierGroupIds,
            /** Serving hours, minutes after midnight Nairobi time; set both. {@code clearServingHours} removes them. */
            @Min(0) @Max(1439) Integer serveFromMinute,
            @Min(0) @Max(1439) Integer serveToMinute,
            Boolean clearServingHours,
            /** Happy hour for a dish: a price, a start and an end. {@code clearHappyHour} removes it. */
            @DecimalMin(value = "0.01") BigDecimal happyPrice,
            @Min(0) @Max(1439) Integer happyFromMinute,
            @Min(0) @Max(1439) Integer happyToMinute,
            /** Days as digits 1 (Monday) to 7 (Sunday); empty means every day. */
            @jakarta.validation.constraints.Pattern(regexp = "^[1-7]{0,7}$") String happyDays,
            Boolean clearHappyHour,
            /** A line or two for the customer. Blank clears it; left out, it is not changed. */
            @Size(max = 500) String description) {
    }

    public record AvailabilityRequest(@NotNull Boolean available) {
    }

    public record OptionRequest(
            /** Set to keep an existing option (and its history); left out for a new one. */
            String id,
            @NotBlank @Size(max = 120) String name,
            @DecimalMin(value = "0.00") BigDecimal priceDelta,
            Boolean preselected,
            Integer sortOrder) {
    }

    public record ModifierGroupRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull @Min(0) @Max(20) Integer minSelect,
            @NotNull @Min(1) @Max(20) Integer maxSelect,
            Integer sortOrder,
            @NotNull @Size(min = 1, max = 30) @Valid List<OptionRequest> options) {
    }

    public record StationRequest(@NotBlank @Size(max = 80) String name, Integer sortOrder, Boolean active) {
    }

    // ---------------------------------------------------------------- kitchen and orders

    /**
     * A modifier option as it was when the line was sold.
     *
     * @param optionId the option's id, so its recipe can be found; absent on lines sold before recipes existed
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record ChosenModifier(String group, String option, BigDecimal priceDelta, String optionId) {

        public ChosenModifier(String group, String option, BigDecimal priceDelta) {
            this(group, option, priceDelta, null);
        }
    }

    public record PrepBatchRequest(
            @NotBlank String branchId,
            /** How much of the prepared item was made, in its own stock unit. */
            @NotNull @DecimalMin("0.0001") BigDecimal quantity) {
    }

    public record PrepBatchResponse(String itemId, BigDecimal quantity, BigDecimal cost) {
    }

    // ---------------------------------------------------------------- settings

    /**
     * @param serviceChargePercent added to every bill of a table's order; null means none
     * @param tipsPooled true: a tip is shared equally among everyone marked as serving customers
     * @param dayStartsAtMinute minutes after midnight, Nairobi time, at which a report's day starts
     */
    public record SettingsResponse(
            BigDecimal serviceChargePercent, boolean tipsPooled, BigDecimal packagingCharge,
            BigDecimal deliveryFee, int dayStartsAtMinute, boolean qrOrdering,
            /** {@code BUSINESS}: the service charge is the restaurant's income. {@code STAFF}: it is shared like a tip. */
            String serviceChargeTo,
            /** Whether the menu is shown on the restaurant's public web page. */
            boolean menuOnline) {
    }

    /** Null leaves a field as it is; zero switches a charge off. */
    public record SettingsRequest(
            @DecimalMin("0") BigDecimal serviceChargePercent,
            Boolean tipsPooled,
            @DecimalMin("0") BigDecimal packagingCharge,
            @DecimalMin("0") BigDecimal deliveryFee,
            @Min(0) @Max(1439) Integer dayStartsAtMinute,
            Boolean qrOrdering,
            @jakarta.validation.constraints.Pattern(regexp = "BUSINESS|STAFF") String serviceChargeTo,
            Boolean menuOnline) {

        public SettingsRequest(
                BigDecimal serviceChargePercent, Boolean tipsPooled, BigDecimal packagingCharge,
                BigDecimal deliveryFee, Integer dayStartsAtMinute, Boolean qrOrdering) {
            this(serviceChargePercent, tipsPooled, packagingCharge, deliveryFee, dayStartsAtMinute, qrOrdering, null, null);
        }
    }

    // ---------------------------------------------------------------- recipes

    public record RecipeIngredientRequest(
            @NotBlank String itemId,
            /** What ends up on the plate, in the ingredient's own stock unit. */
            @NotNull @DecimalMin("0.0001") BigDecimal quantity,
            /** 1 to 100: how much of what is taken from stock survives trimming and cooking. Empty means all of it. */
            @DecimalMin("1") @jakarta.validation.constraints.DecimalMax("100") BigDecimal yieldPercent,
            /** A stocked product to use instead, in the same quantity, when this one has run out. Optional. */
            String substituteItemId) {

        public RecipeIngredientRequest(String itemId, BigDecimal quantity, BigDecimal yieldPercent) {
            this(itemId, quantity, yieldPercent, null);
        }
    }

    public record RecipeRequest(@NotNull @Size(max = 40) @Valid List<RecipeIngredientRequest> ingredients) {
    }

    /**
     * @param takenFromStock the plated quantity scaled up for what is lost
     * @param unitCost the ingredient's buying price now; an estimate — the sale uses what the stock actually cost
     */
    public record RecipeIngredientResponse(
            String itemId, String name, String unit, BigDecimal quantity, BigDecimal yieldPercent,
            BigDecimal takenFromStock, BigDecimal unitCost, BigDecimal cost, BigDecimal onHand,
            /** The stand-in used when this ingredient has run out, if one was named. */
            String substituteItemId, String substituteName, BigDecimal substituteOnHand) {
    }

    /** @param costKnown false when an ingredient has no buying price, so the total is short */
    public record RecipeResponse(List<RecipeIngredientResponse> ingredients, BigDecimal estimatedCost, boolean costKnown) {
    }

    // ---------------------------------------------------------------- kitchen requisitions

    public record RequisitionLineRequest(
            @NotBlank String itemId,
            @NotNull @DecimalMin("0.0001") @jakarta.validation.constraints.DecimalMax("999999") BigDecimal quantity) {
    }

    /** @param clientKey made once by the device asking; sending the request again changes nothing */
    public record RequisitionRequest(
            @NotBlank String branchId,
            String stationId,
            @Size(max = 255) String note,
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "^[A-Za-z0-9-]{8,64}$") String clientKey,
            @NotNull @Size(min = 1, max = 40) @Valid List<RequisitionLineRequest> lines) {
    }

    public record IssuedLineRequest(
            @NotBlank String lineId,
            @NotNull @DecimalMin("0") @jakarta.validation.constraints.DecimalMax("999999") BigDecimal quantity) {
    }

    /** @param lines what was actually given, line by line; a line left out was given in full */
    public record IssueRequisitionRequest(
            @Size(max = 40) @Valid List<IssuedLineRequest> lines, @Size(max = 255) String note) {
    }

    public record RefuseRequisitionRequest(@NotBlank @Size(max = 255) String reason) {
    }

    /** @param onHand the product's stock count now, so whoever issues can see what there is */
    public record RequisitionLineResponse(
            String id, String itemId, String name, String unit, BigDecimal quantityRequested,
            BigDecimal quantityIssued, BigDecimal onHand) {
    }

    /** @param status {@code REQUESTED}, {@code ISSUED}, {@code REFUSED} or {@code CANCELLED} */
    public record RequisitionResponse(
            String id, long number, String branchId, String stationId, String stationName, String status,
            String note, String requestedByName, Instant requestedAt, String decidedByName, Instant decidedAt,
            String decisionNote, List<RequisitionLineResponse> lines) {
    }

    /** @param waiting requisitions nobody has decided yet, oldest first; @param recent the last decided ones */
    public record RequisitionListResponse(
            String branchId, List<RequisitionResponse> waiting, List<RequisitionResponse> recent) {
    }

    /** A product the kitchen can ask the store for. No price: a cook is not shown money. */
    public record RequisitionProductResponse(String id, String name, String unit) {
    }

    public record KitchenLineResponse(
            String id, String orderLineId, String name, BigDecimal quantity, List<ChosenModifier> modifiers,
            String note, String stage, Instant readyAt) {
    }

    public record KitchenTicketResponse(
            String id, String orderId, long orderNumber, String stationId, String ticketType, String status,
            int roundNo, Instant sentAt, Instant readyAt, List<KitchenLineResponse> lines) {
    }

    public record KitchenBoardResponse(String branchId, Instant asOf, List<KitchenTicketResponse> tickets) {
    }

    public record StageRequest(@NotBlank String stage) {
    }

    /** An order as the counter sees it: its number and whether everything sent to the kitchen is ready. */
    public record OrderSummaryResponse(
            String orderId, long orderNumber, String saleId, Instant sentAt, boolean ready,
            int linesTotal, int linesReady, List<String> lineNames) {
    }

    public record OrderListResponse(String branchId, Instant asOf, List<OrderSummaryResponse> orders) {
    }

    // ---------------------------------------------------------------- reports

    /**
     * @param cost what the ingredients cost; null when the dish has no recipe
     * @param margin revenue less cost, and {@code costPercent} cost as a share of revenue; null without a recipe
     * @param engineering {@code STAR} (sells well, earns well), {@code PLOWHORSE} (sells well, earns little),
     *        {@code PUZZLE} (earns well, sells little) or {@code DOG}; null without a recipe
     */
    public record MenuItemSalesRow(
            String menuItemId, String name, BigDecimal quantity, BigDecimal revenue, BigDecimal sharePercent,
            BigDecimal cost, BigDecimal margin, BigDecimal costPercent, String engineering) {
    }

    public record ModifierSalesRow(String group, String option, long timesChosen, BigDecimal extraRevenue) {
    }

    /**
     * @param costTracked true once the business has written any recipe. A dish with no recipe still
     *        shows no cost, so {@code totalCost} covers only the dishes that have one.
     */
    public record MenuSalesReport(
            String from, String to, BigDecimal totalRevenue, boolean costTracked, BigDecimal totalCost,
            List<MenuItemSalesRow> items, List<ModifierSalesRow> modifiers) {
    }

    public record StationTimesRow(String stationId, String stationName, long tickets, long medianSeconds, long slowestSeconds) {
    }

    public record KitchenTimesReport(String from, String to, List<StationTimesRow> stations) {
    }
}
