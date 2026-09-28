package zelisline.ub.payments.application;

/**
 * Who is asking for an STK prompt.
 *
 * <p>{@link #CASHIER} is a signed-in till: Daraja is available as soon as the
 * method is active, and cash stays the default tender in the POS.
 * {@link #STOREFRONT} is a public shop: Daraja stays hidden until Super Admin
 * approves the merchant's request.
 */
public enum StkAudience {
    CASHIER,
    STOREFRONT
}
