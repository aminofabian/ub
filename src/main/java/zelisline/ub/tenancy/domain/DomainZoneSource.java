package zelisline.ub.tenancy.domain;

/**
 * Who serves forward DNS for this hostname.
 * {@link #VERCEL} = platform-owned zone (purchase path); {@link #EXTERNAL} = BYO / merchant DNS.
 */
public enum DomainZoneSource {
    VERCEL,
    EXTERNAL
}
