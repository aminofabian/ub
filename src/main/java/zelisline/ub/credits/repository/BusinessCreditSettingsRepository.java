package zelisline.ub.credits.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.credits.domain.BusinessCreditSettings;

public interface BusinessCreditSettingsRepository extends JpaRepository<BusinessCreditSettings, String> {

    /** Meta phone number id that receives the shop's WhatsApp messages. */
    Optional<BusinessCreditSettings> findByWhatsappMetaPhoneNumberId(String phoneNumberId);
}
