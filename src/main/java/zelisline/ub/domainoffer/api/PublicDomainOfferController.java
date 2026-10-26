package zelisline.ub.domainoffer.api;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.domainoffer.api.dto.PublicDomainOfferDtos;
import zelisline.ub.domainoffer.application.DomainOfferService;

/**
 * Tokenised public offer page. The token is the credential: it exposes only the
 * shop's public identity, the requested domain/theme, and payment state. No
 * tenant session or staff data is required.
 */
@Validated
@RestController
@RequestMapping("/api/v1/public/domain-offer")
@RequiredArgsConstructor
public class PublicDomainOfferController {

    private final DomainOfferService domainOfferService;

    @GetMapping("/{token}")
    public PublicDomainOfferDtos.PublicDomainOfferResponse offer(@PathVariable String token) {
        return domainOfferService.publicOffer(token);
    }

    @PostMapping("/{token}/pay")
    public PublicDomainOfferDtos.PublicPayResponse pay(
            @PathVariable String token,
            @Valid @RequestBody PublicDomainOfferDtos.PublicPayRequest body
    ) {
        return domainOfferService.pay(token, body);
    }

    @GetMapping("/{token}/status")
    public PublicDomainOfferDtos.PublicStatusResponse status(@PathVariable String token) {
        return domainOfferService.publicStatus(token);
    }
}
