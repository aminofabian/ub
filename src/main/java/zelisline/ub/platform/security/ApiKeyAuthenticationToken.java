package zelisline.ub.platform.security;

import java.util.Collections;

import org.springframework.security.authentication.AbstractAuthenticationToken;

/** Authenticated integration key — authorization uses scopes via {@link DataPermissionEvaluator}. */
public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    private final ApiKeyPrincipal principal;

    public ApiKeyAuthenticationToken(ApiKeyPrincipal principal) {
        super(Collections.emptyList());
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public ApiKeyPrincipal getPrincipal() {
        return principal;
    }
}
