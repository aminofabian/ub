package zelisline.ub.storefront.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.storefront.domain.ShopperCheckoutProfile;

public interface ShopperCheckoutProfileRepository extends JpaRepository<ShopperCheckoutProfile, String> {

    Optional<ShopperCheckoutProfile> findByBusinessIdAndUserId(String businessId, String userId);

    Optional<ShopperCheckoutProfile> findByBusinessIdAndGuestKey(String businessId, String guestKey);
}
