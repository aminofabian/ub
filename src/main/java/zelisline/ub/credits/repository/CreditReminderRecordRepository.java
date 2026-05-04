package zelisline.ub.credits.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.credits.domain.CreditReminderRecord;

public interface CreditReminderRecordRepository extends JpaRepository<CreditReminderRecord, String> {

    boolean existsByCreditAccountIdAndWeekBucket(String creditAccountId, String weekBucket);
}
