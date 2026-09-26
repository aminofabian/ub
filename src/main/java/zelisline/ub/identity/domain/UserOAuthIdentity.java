package zelisline.ub.identity.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(
        name = "user_oauth_identities",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_uoi_provider_subject_business",
                        columnNames = {"provider", "provider_subject", "business_id"}),
                @UniqueConstraint(
                        name = "uq_uoi_user_provider",
                        columnNames = {"user_id", "provider"})
        })
@Getter
@Setter
public class UserOAuthIdentity {

    public static final String PROVIDER_GOOGLE = "google";

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(name = "business_id", length = 36, nullable = false)
    private String businessId;

    @Column(name = "user_id", length = 36, nullable = false)
    private String userId;

    @Column(length = 32, nullable = false)
    private String provider;

    @Column(name = "provider_subject", length = 255, nullable = false)
    private String providerSubject;

    @Column(name = "email_at_link", length = 191)
    private String emailAtLink;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
