package zelisline.ub.tenancy.api.dto;

import jakarta.validation.Valid;

public record FeatureFlagsPatchRequest(
        @Valid PosDraftsFeatureFlagsPatch posDrafts,
        /** Enable the butcher counter POS workspace and weighed-sale features. */
        Boolean butcherPosEnabled,
        /** Allow cashiers to override shelf prices at POS. */
        Boolean posCashierPriceEdit,
        /** Allow cashiers to quick-create products at POS. */
        Boolean posCashierCreateProduct,
        /** Allow cashiers to mark items as sold by weight from the POS cart. */
        Boolean posCashierWeighedToggle,
        /** Allow owners/admins to upload product photos from the cashier shelf. */
        Boolean posCashierAddPhoto,
        /** Show Order pad on the cashier till (default on when absent). */
        Boolean posCashierOrderPad,
        /** Show Confirm orders on the cashier till (default on when absent). */
        Boolean posCashierOrderConfirm,
        /** Allow cashiers to record cash drawouts from an open till. */
        Boolean posCashierDrawout,
        /** Show Clear sale on the cashier till (default on when absent). */
        Boolean posCashierClearSale,
        /**
         * Allow Clear on the till to void every open tab/draft at once.
         * Absent / false keeps single-tab clear (accountability default).
         */
        Boolean posCashierClearAllSales,
        /**
         * Cashiers may type an M-Pesa transaction code from the customer's message.
         * Absent / false keeps checkout on the prompt only.
         */
        Boolean posCashierMpesaCode,
        /**
         * Search-first hybrid POS catalog (list results + frequent chips).
         * Absent / false keeps the classic product grid.
         */
        Boolean posCatalogHybrid,
        /** Prefill opening float denominations from the previous night's closing count. */
        Boolean shiftsPrefillOpeningFromLastClose,
        /** When M-Pesa till listening starts (POS + storefront). */
        @Valid PosTillListenFeatureFlagsPatch tillListen,
        /** Business hub live beeps on /business. */
        @Valid HubAlertsFeatureFlagsPatch hubAlerts,
        /** Which cashiers may draw out when {@code posCashierDrawout} is on. */
        @Valid CashierDrawoutAccessPatch posCashierDrawoutAccess,
        /** Show the Kiosk opening screen while pages load (default on when absent). */
        Boolean uiOpeningLoader,
        /**
         * Turn cash-drawer shifts off for the shop: cashiers are never asked to open one and
         * sales ride on a hidden system-managed shift. Absent / false keeps shifts required.
         */
        Boolean shiftsDisabled
) {
}
