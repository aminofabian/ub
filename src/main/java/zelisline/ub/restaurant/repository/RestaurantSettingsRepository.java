package zelisline.ub.restaurant.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.restaurant.domain.RestaurantSettings;

public interface RestaurantSettingsRepository extends JpaRepository<RestaurantSettings, String> {
}
