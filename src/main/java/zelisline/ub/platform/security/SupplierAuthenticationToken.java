package zelisline.ub.platform.security;

import java.util.Collection;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

/**
 * Authentication token for supplier portal JWT sessions.
 */
public class SupplierAuthenticationToken extends AbstractAuthenticationToken {

    private final SupplierPrincipal principal;

    public SupplierAuthenticationToken(SupplierPrincipal principal, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public SupplierPrincipal getPrincipal() {
        return principal;
    }
}
