package zelisline.ub.grocery.api;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.grocery.application.GroceryInvoiceService;

@Component
@RequiredArgsConstructor
public class GroceryInvoiceExpiryScheduler {

    private final GroceryInvoiceService service;

    @Scheduled(fixedRate = 300_000) // every 5 minutes
    public void expireStaleInvoices() {
        service.expireInvoices();
    }
}
