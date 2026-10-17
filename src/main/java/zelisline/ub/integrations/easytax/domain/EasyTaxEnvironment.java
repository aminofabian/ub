package zelisline.ub.integrations.easytax.domain;

/**
 * Which EasyTax deployment a business points at. {@code SANDBOX} is Pergamon's
 * shared test environment; {@code PRODUCTION} is the shop's approved VSCU.
 */
public enum EasyTaxEnvironment {
    SANDBOX,
    PRODUCTION
}
