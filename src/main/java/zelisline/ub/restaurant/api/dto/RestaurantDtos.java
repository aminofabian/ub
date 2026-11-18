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

    public record StationResponse(String id, String name, int sortOrder, boolean active,
            /** The station's own printer: a CUPS/printer name, or a network printer's host and port. All null means none. */
            String printerName, String printerHost, Integer printerPort) {
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
            String imageUrl,
            /**
             * How many more the branch's usable stock can make, if only this were made (ADR 0026).
             * Null when it was not asked for, or the dish has no recipe of counted ingredients.
             */
            Long canMake,
            /** The ingredient that runs out first; null with {@code canMake}. */
            String limitingIngredient,
            /** How many of {@code canMake} depend on stock that expires today; null with it. */
            Long mustUseToday) {
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

    // ---------------------------------------------------------------- bar measures

    /**
     * One pour off a bottle: a tot, a single, a double, a glass. The quantity taken from the bottle
     * is worked out from the pour and bottle sizes — the owner never hand-computes 30/750. The owner
     * gives the <em>price the customer pays</em> for the pour, not a surcharge: the helper works out
     * the dish base and per-pour add-ons so a double rings at exactly its price.
     *
     * @param label        what the customer sees ("Single", "Double", "125 ml glass")
     * @param pourMl       how much liquid this pour is, in the same unit as {@code bottleMl}
     * @param price        the whole price a customer pays for this pour
     * @param preselected  true to make this the default pour on the drink
     */
    public record MeasurePour(
            @NotBlank @Size(max = 120) String label,
            @NotNull @DecimalMin("0.0001") BigDecimal pourMl,
            @NotNull @DecimalMin(value = "0.01") BigDecimal price,
            Boolean preselected) {
    }

    /**
     * Sets up the pour measures of one bottle. It builds a "choose one" modifier group whose options
     * are the pours, each with its own recipe taking its fraction of the one stocked bottle, so ringing
     * a double depletes the bottle by {@code pourMl / bottleMl}. Re-running with the same group name
     * replaces the pours. A bottle is a stocked product; the drink that carries the group is optional.
     *
     * @param bottleItemId   the stocked product the pours come out of (the bottle)
     * @param bottleMl       the whole bottle's size, in the bottle's own stock unit
     * @param groupName      the modifier group's name; null means "Measure"
     * @param dishMenuItemId a dish to attach the group to, so it is pourable at once; null to attach later
     * @param pours          the pours, 1 to 20
     */
    public record MeasuresRequest(
            @NotBlank String bottleItemId,
            @NotNull @DecimalMin("0.0001") BigDecimal bottleMl,
            @Size(max = 120) String groupName,
            String dishMenuItemId,
            @NotNull @Size(min = 1, max = 20) @Valid List<MeasurePour> pours) {
    }

    /** One generated pour: the option that rings it, the bottle fraction its recipe takes, and its price. */
    public record MeasurePourResult(String optionId, String label, BigDecimal quantity, BigDecimal price) {
    }

    /** The modifier group the measures helper built (or replaced) and the pours on it. */
    public record MeasuresResponse(String groupId, String bottleItemId, BigDecimal bottleMl,
            List<MeasurePourResult> pours) {
    }

    public record StationRequest(@NotBlank @Size(max = 80) String name, Integer sortOrder, Boolean active,
            /**
             * The station's printer. A CUPS/printer name, or a network printer's host and a port
             * (1..65535). Set a name, or a host and port, not both. {@code clearPrinter} removes it.
             */
            @Size(max = 191) String printerName,
            @Size(max = 191) String printerHost,
            @Min(1) @Max(65535) Integer printerPort,
            Boolean clearPrinter) {
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
            boolean menuOnline,
            /** Default room check-in time, "HH:mm" Nairobi, for a lodging tenant (ADR 0031). */
            String checkInTime,
            /** Default room check-out time, "HH:mm" Nairobi, for a lodging tenant (ADR 0031). */
            String checkOutTime,
            /** Whether rooms are shown and bookable on the public storefront (ADR 0031). */
            boolean roomsOnline) {
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
            Boolean menuOnline,
            /** "HH:mm" Nairobi, or null to leave as is; empty string clears to the 2pm default. */
            @jakarta.validation.constraints.Pattern(regexp = "|([01]\\d|2[0-3]):[0-5]\\d") String checkInTime,
            @jakarta.validation.constraints.Pattern(regexp = "|([01]\\d|2[0-3]):[0-5]\\d") String checkOutTime,
            Boolean roomsOnline) {

        public SettingsRequest(
                BigDecimal serviceChargePercent, Boolean tipsPooled, BigDecimal packagingCharge,
                BigDecimal deliveryFee, Integer dayStartsAtMinute, Boolean qrOrdering) {
            this(serviceChargePercent, tipsPooled, packagingCharge, deliveryFee, dayStartsAtMinute, qrOrdering,
                    null, null, null, null, null);
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

    /**
     * A kitchen ticket waiting to be printed, with the ticket itself and the station's printer (if
     * it named one) so a device can print without another read. {@code reprint} is true for a
     * ticket the counter asked to print again.
     */
    public record KitchenPrintJobResponse(
            String id, String ticketId, long orderNumber, String stationId, String stationName,
            String printerName, String printerHost, Integer printerPort, boolean reprint,
            KitchenTicketResponse ticket) {
    }

    /** A kitchen ticket the counter should warn has not printed. */
    public record UnprintedTicketResponse(
            String jobId, String ticketId, long orderNumber, String stationId, String ticketType, Instant sentAt) {
    }

    public record KitchenPrintJobsResponse(List<KitchenPrintJobResponse> jobs) {
    }

    public record UnprintedTicketsResponse(List<UnprintedTicketResponse> tickets) {
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

    /**
     * What one table took over a period, from the bills paid at it.
     *
     * @param tableId   null for a row that is not a table: tabs, takeaway, delivery
     * @param waiter    the waiter assigned to the table now; null when none
     * @param orders    orders with at least one paid bill in the period
     * @param guests    guests on those orders, where a number was given
     * @param taken     what the paid bills came to, service charge included, tips not
     * @param perOrder  {@code taken} over {@code orders}
     */
    public record TableTakingsRow(
            String tableId, String name, String area, String waiter, long orders, long guests, long bills,
            BigDecimal taken, BigDecimal tips, BigDecimal perOrder) {
    }

    /** What one waiter's orders took: the orders they served, whoever took the money. */
    public record WaiterTakingsRow(String staffProfileId, String name, long orders, BigDecimal taken, BigDecimal tips) {
    }

    /**
     * Takings by table and by waiter for a period, counted in business days. Paid bills only: a
     * bill on the house or left unpaid took nothing, a voided sale is left out, and refunds made
     * afterwards are not taken off. Sales rung at the counter are not table orders and are not here.
     */
    public record TableTakingsReport(
            String from, String to, BigDecimal taken, BigDecimal tips, long orders,
            List<TableTakingsRow> tables, List<TableTakingsRow> offTheFloor, List<WaiterTakingsRow> waiters) {
    }

    public record StationTimesRow(String stationId, String stationName, long tickets, long medianSeconds, long slowestSeconds) {
    }

    public record KitchenTimesReport(String from, String to, List<StationTimesRow> stations) {
    }
}
