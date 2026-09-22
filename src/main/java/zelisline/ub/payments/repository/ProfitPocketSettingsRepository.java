package zelisline.ub.payments.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import zelisline.ub.payments.domain.ProfitPocketSettings;

public interface ProfitPocketSettingsRepository extends JpaRepository<ProfitPocketSettings, String> {

    @Query("""
            select s from ProfitPocketSettings s
            where s.enabled = true
              and s.fridayReminderEnabled = true
              and s.destinationType is not null
            """)
    List<ProfitPocketSettings> findFridayReminderCandidates();
}
