package zelisline.ub.tenancy.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/**
 * Optional CMS-lite fields for landing / coming-soon templates.
 * Front-window and brand-poster templates use extended copy and image fields.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LandingContentDto(
        @Size(max = 120) String headline,
        @Size(max = 280) String subheadline,
        @Size(max = 40) String phone,
        @Size(max = 40) String whatsapp,
        @Size(max = 200) String hours,
        @Size(max = 200) String address,
        @Size(max = 64) String ctaLabel,
        @Size(max = 2048) String vitrineImageUrl,
        @Size(max = 2048) String storyImageUrl,
        @Size(max = 2048) String visitImageUrl,
        @Size(max = 120) String storyTitle,
        @Size(max = 600) String storyBody,
        @Size(max = 280) String storyQuote,
        @Size(max = 120) String carryTitle,
        @Size(max = 280) String carryLead,
        @Size(max = 120) String visitTitle,
        @Size(max = 280) String holdAtCounterNote,
        @Size(max = 120) String contactTitle,
        @Size(max = 280) String contactBody,
        @Size(max = 64) String secondaryCtaLabel,
        @Size(max = 48) String navStoryLabel,
        @Size(max = 48) String navCarryLabel,
        @Size(max = 48) String navVisitLabel,
        @Size(max = 48) String navContactLabel,
        @Valid List<LandingHighlightDto> highlights,
        @Size(max = 80) String posterTagline,
        @Size(max = 16) String posterEditionText,
        @Size(max = 120) String posterSpineText,
        @Size(max = 48) String posterBadgeLabel,
        @Size(max = 200) String posterContactLead,
        @Size(max = 2048) String posterSecondaryImageUrl,
        @Size(max = 8) String posterTone
) {
}
