package zelisline.ub.domainoffer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import zelisline.ub.domainoffer.application.DomainOfferService;
import zelisline.ub.domainoffer.domain.DomainOfferCampaign;
import zelisline.ub.domainoffer.domain.DomainOfferCampaignStatus;
import zelisline.ub.domainoffer.domain.DomainOfferDomainMode;
import zelisline.ub.domainoffer.domain.DomainOfferOrder;
import zelisline.ub.domainoffer.domain.DomainOfferStatus;
import zelisline.ub.domainoffer.domain.DomainOfferThemeDirection;
import zelisline.ub.domainoffer.repository.DomainOfferCampaignRepository;
import zelisline.ub.domainoffer.repository.DomainOfferOrderRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Exercises the campaign + order read queries against a real (H2) schema.
 * The wiring IT proves the beans load; this proves the filters and public
 * projection actually run.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.domain-offer.scheduler-enabled=false"
})
@ActiveProfiles("test")
class DomainOfferQueryIT {

    @Autowired
    private DomainOfferService domainOfferService;

    @Autowired
    private DomainOfferCampaignRepository campaignRepository;

    @Autowired
    private DomainOfferOrderRepository orderRepository;

    @Autowired
    private BusinessRepository businessRepository;

    @Test
    void campaignAndOrdersCanBeListedAndReadByToken() {
        Business business = new Business();
        business.setName("Powermart Query Test");
        business.setSlug("powermart-query-test");
        business.setCurrency("KES");
        business.setCountryCode("KE");
        business.setTimezone("Africa/Nairobi");
        Business savedBusiness = businessRepository.save(business);

        DomainOfferCampaign campaign = new DomainOfferCampaign();
        campaign.setName("Query test");
        campaign.setStatus(DomainOfferCampaignStatus.RUNNING);
        campaign.setStartedAt(Instant.now());
        campaign.setRecipientsTargeted(1);
        campaign.setCreatedBySuperAdminId("sa-test");
        DomainOfferCampaign savedCampaign = campaignRepository.save(campaign);

        DomainOfferOrder order = new DomainOfferOrder();
        order.setCampaignId(savedCampaign.getId());
        order.setBusinessId(savedBusiness.getId());
        order.setReference("DTO-QUERY1");
        order.setPayToken("query-token-000000000000000000000000000000000000000000000000");
        order.setStatus(DomainOfferStatus.INVITED);
        order.setDomainMode(DomainOfferDomainMode.OWNED);
        order.setSuggestedDomain("powermart-query-test.co.ke");
        order.setThemeKey("mart");
        order.setThemeDirection(DomainOfferThemeDirection.WARM_MARKET.name());
        order.setContactName("Amina");
        order.setContactEmail("amina@example.com");
        order.setAmountCents(DomainOfferCampaign.DEFAULT_PRICE_CENTS);
        order.setTouchCount(1);
        order.setLastTouchAt(Instant.now());
        order.setNextTouchAt(Instant.now().plus(3, ChronoUnit.DAYS));
        orderRepository.save(order);

        var list = domainOfferService.listOrders(null, "powermart", 25);
        assertThat(list.rows()).hasSize(1);
        assertThat(list.rows().getFirst().businessName()).isEqualTo("Powermart Query Test");
        assertThat(list.rows().getFirst().payLink()).contains("/custom-domain?t=query-token");
        assertThat(list.stats().invited()).isEqualTo(1);

        var filtered = domainOfferService.listOrders("INVITED", null, 25);
        assertThat(filtered.rows()).hasSize(1);

        var publicOffer = domainOfferService.publicOffer(order.getPayToken());
        assertThat(publicOffer.businessName()).isEqualTo("Powermart Query Test");
        assertThat(publicOffer.status()).isEqualTo("INVITED");
        assertThat(publicOffer.suggestedDomain()).isEqualTo("powermart-query-test.co.ke");
        assertThat(publicOffer.canPay()).isFalse();
        assertThat(publicOffer.paymentUnavailableReason()).isNotBlank();

        var campaignView = domainOfferService.getCampaign();
        assertThat(campaignView.status()).isEqualTo("RUNNING");
        assertThat(campaignView.touches()).hasSize(3);

        var preview = domainOfferService.publicOffer(DomainOfferService.PREVIEW_TOKEN);
        assertThat(preview.preview()).isTrue();
        assertThat(preview.canPay()).isFalse();
        assertThat(preview.businessName()).isEqualTo("Powermart");
        assertThat(preview.suggestedDomain()).isEqualTo("powermart.co.ke");
    }
}
