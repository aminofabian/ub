package zelisline.ub.identity.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.identity.domain.SuperAdmin;

public interface SuperAdminRepository extends JpaRepository<SuperAdmin, String> {

    Optional<SuperAdmin> findByEmailAndActiveTrue(String email);

    Optional<SuperAdmin> findByEmail(String email);

    List<SuperAdmin> findByActiveTrue();
}
