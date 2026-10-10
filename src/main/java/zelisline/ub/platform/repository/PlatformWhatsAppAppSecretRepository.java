package zelisline.ub.platform.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.platform.domain.PlatformWhatsAppAppSecret;

public interface PlatformWhatsAppAppSecretRepository
        extends JpaRepository<PlatformWhatsAppAppSecret, String> {}
