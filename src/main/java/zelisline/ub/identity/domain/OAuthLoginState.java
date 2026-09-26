package zelisline.ub.identity.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "oauth_login_states")
@Getter
@Setter
public class OAuthLoginState {

    public static final String INTENT_SIGN_IN = "sign_in";
    public static final String INTENT_SIGN_UP = "sign_up";

    @Id
    @Column(name = "state_hash", length = 64, nullable = false)
    private String stateHash;

    /** Short-lived PKCE verifier; plaintext is fine — row is single-use and purged after expiry. */
    @Column(name = "code_verifier", length = 128, nullable = false)
    private String codeVerifier;

    @Column(length = 32, nullable = false)
    private String intent;

    @Column(name = "next_path", length = 512)
    private String nextPath;

    @Column(name = "business_id", length = 36)
    private String businessId;

    @Column(name = "onboard_draft_json", columnDefinition = "TEXT")
    private String onboardDraftJson;

    @Column(name = "browser_binding", length = 64, nullable = false)
    private String browserBinding;

    @Column(length = 64, nullable = false)
    private String nonce;

    @Column(name = "redirect_uri", length = 512, nullable = false)
    private String redirectUri;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;
}
