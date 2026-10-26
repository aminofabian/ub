package zelisline.ub.domainoffer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import zelisline.ub.domainoffer.application.DomainOfferCopy;
import zelisline.ub.domainoffer.domain.DomainOfferThemeDirection;

class DomainOfferCopyTest {

    @Test
    void sequenceHasThreeTouchesOnDayZeroThreeSix() {
        List<DomainOfferCopy.Touch> touches = DomainOfferCopy.touches();

        assertThat(touches).hasSize(3);
        assertThat(touches).extracting(DomainOfferCopy.Touch::dayOffset).containsExactly(0, 3, 6);
        assertThat(touches).extracting(DomainOfferCopy.Touch::index).containsExactly(1, 2, 3);
    }

    @Test
    void touchesCarryTheOfferFactsAndPlaceholders() {
        List<DomainOfferCopy.Touch> touches = DomainOfferCopy.touches();

        assertThat(touches.getFirst().bodyMarkdown()).contains("{{price}}", "{{suggestedDomain}}", "{{currentHost}}");
        assertThat(touches.getFirst().bodyMarkdown()).contains("KES 2,000/year");
        assertThat(touches.get(2).bodyMarkdown()).contains("24 hours");
        assertThat(touches).allSatisfy(touch -> {
            assertThat(touch.subject()).isNotBlank();
            assertThat(touch.cta()).isNotBlank();
            assertThat(touch.inAppTitle()).isNotBlank();
            assertThat(touch.inAppBody()).isNotBlank();
        });
    }

    @Test
    void themeDirectionParsesWireValuesAndFallsBackToDesignerChoice() {
        assertThat(DomainOfferThemeDirection.fromWire("warm-market"))
                .isEqualTo(DomainOfferThemeDirection.WARM_MARKET);
        assertThat(DomainOfferThemeDirection.fromWire("CLEAN_MODERN"))
                .isEqualTo(DomainOfferThemeDirection.CLEAN_MODERN);
        assertThat(DomainOfferThemeDirection.fromWire("bold loud"))
                .isEqualTo(DomainOfferThemeDirection.BOLD_LOUD);
        assertThat(DomainOfferThemeDirection.fromWire(null))
                .isEqualTo(DomainOfferThemeDirection.DESIGNER_CHOICE);
        assertThat(DomainOfferThemeDirection.fromWire("something-else")).isNull();
    }
}
