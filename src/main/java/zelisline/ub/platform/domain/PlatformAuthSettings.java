package zelisline.ub.platform.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "platform_auth_settings")
@Getter
@Setter
public class PlatformAuthSettings {

    public static final String SINGLETON_ID = "00000000-0000-0000-0000-000000000001";

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(name = "email_verification_required", nullable = false)
    private boolean emailVerificationRequired = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
