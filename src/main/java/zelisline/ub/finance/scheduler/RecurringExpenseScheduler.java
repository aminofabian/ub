package zelisline.ub.finance.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.finance.application.RecurringExpenseService;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.finance.recurring-expenses.enabled", havingValue = "true")
public class RecurringExpenseScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecurringExpenseScheduler.class);

    private final RecurringExpenseService recurringExpenseService;

    @Scheduled(cron = "${app.finance.recurring-expenses.cron:0 5 2 * * *}")
    public void runDaily() {
        int posted = recurringExpenseService.processAllBusinessesDueToday();
        log.info("Recurring expense scheduler finished: {} occurrence(s) posted", posted);
    }
}

