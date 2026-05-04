package zelisline.ub.credits;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default {@link Clock} bean for credits scheduling/reminders. Marked
 * {@link ConditionalOnMissingBean} so tests can register a fixed clock without colliding.
 */
@Configuration
public class CreditsTimeConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock systemUtcClock() {
        return Clock.systemUTC();
    }
}
