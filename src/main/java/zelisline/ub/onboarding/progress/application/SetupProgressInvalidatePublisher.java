package zelisline.ub.onboarding.progress.application;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class SetupProgressInvalidatePublisher {

    private final ApplicationEventPublisher eventPublisher;

    public void invalidate(String businessId) {
        if (businessId == null || businessId.isBlank()) {
            return;
        }
        eventPublisher.publishEvent(new SetupProgressUpdatedEvent(businessId.trim()));
    }
}
