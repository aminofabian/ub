package zelisline.ub.storeroom.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.storeroom.domain.StoreRoomSettings;

public interface StoreRoomSettingsRepository extends JpaRepository<StoreRoomSettings, String> {
}
