package zelisline.ub.platform.domain;

import java.util.List;

import zelisline.ub.tenancy.api.dto.StoreTypeCodes;

/**
 * Canonical built-in presentation for every business-type code (ADR 0029). This is the source of
 * truth for a type's default label, hint, display section and order; the {@code business_type_catalog}
 * table overlays these per code. Keep this in step with the frontend {@code STORE_TYPE_OPTIONS}
 * (label/hint) and {@code STORE_TYPE_GROUPS} (section) — the two are a deliberate mirror.
 *
 * <p>Section values: {@code food}, {@code service}, {@code lodging}, {@code shop}. They are derived
 * from {@link StoreTypeCodes}; {@code lodging} gets its own section (it is an add-on format).
 */
public final class BusinessTypeCatalogDefaults {

    /** Display section for a code. Drives the onboarding grouping; never a behaviour branch. */
    public static String sectionFor(String code) {
        if (StoreTypeCodes.FOOD_TYPES.contains(code)) {
            return "food";
        }
        if (StoreTypeCodes.LODGING_TYPES.contains(code)) {
            return "lodging";
        }
        if (StoreTypeCodes.SERVICE_TYPES.contains(code)) {
            return "service";
        }
        return "shop";
    }

    public record Entry(String code, String label, String hint, String section, int displayOrder) {}

    /**
     * All 19 codes in onboarding order. Order and copy mirror the frontend {@code STORE_TYPE_OPTIONS}.
     * {@code displayOrder} is the index × 10 so Super Admin can slot a type between two without a
     * full renumber.
     */
    public static final List<Entry> ALL = buildAll();

    private static List<Entry> buildAll() {
        List<RawEntry> raw = List.of(
                new RawEntry("butchery", "Butchery", "Meat, poultry, fish, eggs, and value-added products"),
                new RawEntry("mini-mart", "Mini mart", "Snacks, drinks, and everyday staples"),
                new RawEntry("full-grocery", "Full grocery", "Wide range across all departments"),
                new RawEntry("fresh-market", "Fresh market", "Produce, dairy, and protein focus"),
                new RawEntry("mixed-shop", "Mixed shop", "Groceries plus general retail"),
                new RawEntry("cosmetics", "Cosmetics", "Beauty, skin care, hair, and grooming"),
                new RawEntry("wines-spirits", "Wines & spirits", "Beer, wine, spirits, and mixers"),
                new RawEntry("pharmacy", "Pharmacy / chemist", "Medicines, OTC, first aid, and health supplies"),
                new RawEntry("barbershop", "Kinyozi / barbershop", "Haircuts and shaves, with what each barber earns"),
                new RawEntry("salon", "Salon", "Hair, braids, colour, and treatments"),
                new RawEntry("spa", "Spa", "Massage, facials, and wellness"),
                new RawEntry("nail-studio", "Nail studio", "Manicure, pedicure, and nail art"),
                new RawEntry("beauty-parlour", "Beauty parlour", "Make-up, lashes, brows, and grooming"),
                new RawEntry("restaurant", "Restaurant", "Meals made to order, with tickets for the kitchen"),
                new RawEntry("cafe", "Café", "Coffee, tea, snacks, and light meals"),
                new RawEntry("fast-food", "Fast food / takeaway", "Chips, chicken, burgers — pay, then collect by number"),
                new RawEntry("bar", "Bar", "Drinks and bites, made at the bar or in the kitchen"),
                new RawEntry("lodging", "Guest house / rooms", "A few rooms to let — on their own, or above a bar"),
                new RawEntry("clinic", "Clinic / consulting room", "Consultations, tests and procedures billed as one visit — on its own, or inside a chemist"),
                new RawEntry("other", "Other", "A different kind of shop — add your own product sections"));

        java.util.ArrayList<Entry> out = new java.util.ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            RawEntry r = raw.get(i);
            out.add(new Entry(r.code(), r.label(), r.hint(), sectionFor(r.code()), (i + 1) * 10));
        }
        return List.copyOf(out);
    }

    private record RawEntry(String code, String label, String hint) {}

    private BusinessTypeCatalogDefaults() {}
}
