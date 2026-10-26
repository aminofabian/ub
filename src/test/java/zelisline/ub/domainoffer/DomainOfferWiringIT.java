package zelisline.ub.domainoffer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import zelisline.ub.domainoffer.application.DomainOfferMessagingService;
import zelisline.ub.domainoffer.application.DomainOfferService;
import zelisline.ub.domainoffer.repository.DomainOfferCampaignRepository;
import zelisline.ub.domainoffer.repository.DomainOfferOrderRepository;

/**
 * Boots the full context with the new offer beans and JPA mappings. The unit
 * suite runs without a schema; this catches bean cycles and mapping mistakes.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.domain-offer.scheduler-enabled=false"
})
@ActiveProfiles("test")
class DomainOfferWiringIT {

    @Autowired
    private DomainOfferService domainOfferService;

    @Autowired
    private DomainOfferMessagingService messagingService;

    @Autowired
    private DomainOfferCampaignRepository campaignRepository;

    @Autowired
    private DomainOfferOrderRepository orderRepository;

    @Test
    void contextLoads() {
        assertThat(domainOfferService).isNotNull();
        assertThat(messagingService).isNotNull();
        assertThat(campaignRepository).isNotNull();
        assertThat(orderRepository).isNotNull();
    }
}
