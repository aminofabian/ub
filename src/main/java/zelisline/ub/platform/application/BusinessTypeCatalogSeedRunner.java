package zelisline.ub.platform.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * At startup, insert a catalogue row for any business-type code that lacks one, from its built-in
 * default (ADR 0029). Idempotent and non-destructive: an existing row (including an edited label or
 * uploaded image) is left untouched. This covers databases older than {@code V405} and any code
 * added to the enum after the table was created.
 */
@Component
@RequiredArgsConstructor
public class BusinessTypeCatalogSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BusinessTypeCatalogSeedRunner.class);

    private final BusinessTypeCatalogService service;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = service.backfillMissingDefaults();
            if (created > 0) {
                log.info("Seeded {} missing business-type catalogue row(s) from defaults", created);
            }
        } catch (RuntimeException e) {
            // Never block startup on seeding; the read model falls back to built-in defaults anyway.
            log.warn("Could not seed business-type catalogue defaults: {}", e.getMessage());
        }
    }
}
