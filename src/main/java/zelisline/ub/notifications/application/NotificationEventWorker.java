package zelisline.ub.notifications.application;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.notifications.domain.NotificationEvent;
import zelisline.ub.notifications.repository.NotificationEventRepository;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationEventWorker {

    private static final int BATCH = 40;

    private final NotificationEventRepository eventRepository;
    private final NotificationEventProcessor processor;

    public void processDue() {
        List<NotificationEvent> batch =
                eventRepository.findPending(PageRequest.of(0, BATCH));
        for (NotificationEvent event : batch) {
            try {
                processor.processEvent(event.getId());
            } catch (RuntimeException ex) {
                log.warn("notification event processing threw eventId={}", event.getId(), ex);
            }
        }
    }
}
