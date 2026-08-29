package zelisline.ub.messaging.scheduler;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.messaging.application.SmsCreditService;
import zelisline.ub.messaging.domain.BusinessSmsCreditAccount;
import zelisline.ub.messaging.repository.BusinessSmsCreditAccountRepository;

/**
 * Monthly included-allowance reset — 05:00 on the 1st, Africa/Nairobi
 * (SMS_CREDITS_SCOPE.md §7). Purchased balance rolls over untouched.
 */
@Component
@RequiredArgsConstructor
public class SmsCreditCycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(SmsCreditCycleScheduler.class);

    private final BusinessSmsCreditAccountRepository accountRepository;
    private final SmsCreditService creditService;

    @Scheduled(cron = "${app.sms-credits.cycle-reset-cron:0 5 1 * * *}", zone = "Africa/Nairobi")
    public void resetCycles() {
        List<BusinessSmsCreditAccount> accounts = accountRepository.findAll();
        int reset = 0;
        for (BusinessSmsCreditAccount account : accounts) {
            try {
                creditService.resetCycle(account.getBusinessId());
                reset++;
            } catch (RuntimeException ex) {
                log.error("SMS credit cycle reset failed business={} error={}",
                        account.getBusinessId(), ex.getMessage());
            }
        }
        log.info("SMS credit cycle reset complete: {} of {} accounts processed", reset, accounts.size());
    }
}
