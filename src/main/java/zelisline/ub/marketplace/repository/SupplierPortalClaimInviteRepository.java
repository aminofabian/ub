package zelisline.ub.marketplace.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.marketplace.domain.SupplierPortalClaimInvite;

public interface SupplierPortalClaimInviteRepository extends JpaRepository<SupplierPortalClaimInvite, String> {

    Optional<SupplierPortalClaimInvite> findFirstByCodeHashAndConsumedAtIsNullOrderByCreatedAtDesc(String codeHash);

    Optional<SupplierPortalClaimInvite> findFirstBySetupTokenHashAndConsumedAtIsNull(String setupTokenHash);
}
