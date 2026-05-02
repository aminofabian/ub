package zelisline.ub.identity.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.identity.domain.PasswordResetToken;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, String> {

    Optional<PasswordResetToken> findByTokenHashAndUsedAtIsNull(String tokenHash);
}
