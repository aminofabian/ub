package zelisline.ub.sales.api.dto;

import jakarta.validation.constraints.NotNull;

/** Cashier cart: mark a catalog item as sold by weight (or clear that flag). */
public record PosSetItemWeighedRequest(@NotNull Boolean weighed) {}
