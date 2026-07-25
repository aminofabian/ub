package zelisline.ub.marketplace.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.marketplace.domain.SupplierUser;

public interface SupplierUserRepository extends JpaRepository<SupplierUser, String> {

    Optional<SupplierUser> findByEmail(String email);

    Optional<SupplierUser> findByPhone(String phone);

    Optional<SupplierUser> findByIdAndMarketplaceSupplierId(String id, String marketplaceSupplierId);

    boolean existsByPhone(String phone);

    boolean existsByEmail(String email);
}
