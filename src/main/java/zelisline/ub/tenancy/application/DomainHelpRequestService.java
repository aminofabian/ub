package zelisline.ub.tenancy.application;

import java.util.Locale;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.platform.adoption.DomainHelpRequestedEvent;
import zelisline.ub.support.api.dto.SendSupportMessageRequest;
import zelisline.ub.support.application.SupportService;
import zelisline.ub.tenancy.api.dto.RequestDomainHelpRequest;
import zelisline.ub.tenancy.api.dto.RequestDomainHelpResponse;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Merchant asks Kiosk to do the domain work. The request lands in the shop's
 * support thread, then texts go out after commit.
 */
@Service
@RequiredArgsConstructor
public class DomainHelpRequestService {

    /** Flat fee for shop look-and-behavior work, in cents. */
    static final long SHOP_CHANGE_FEE_CENTS = 500_000L;

    private record HelpKind(String label, Long feeCents) {}

    private static final Map<String, HelpKind> KINDS = Map.of(
            "setup_domain", new HelpKind("Buy and connect a .ke name", null),
            "connect_owned", new HelpKind("Connect a domain they already own", null),
            "shop_online", new HelpKind("Help get the shop online", null),
            "theme", new HelpKind("Theme customization", SHOP_CHANGE_FEE_CENTS),
            "functionality", new HelpKind("Functionality adjustment", SHOP_CHANGE_FEE_CENTS),
            "other_change", new HelpKind("Another change to the shop", SHOP_CHANGE_FEE_CENTS)
    );

    private final BusinessRepository businessRepository;
    private final SupportService supportService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public RequestDomainHelpResponse request(String businessId, String userId, RequestDomainHelpRequest body) {
        if (businessRepository.findByIdAndDeletedAtIsNull(businessId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found");
        }
        String kind = body == null || body.kind() == null
                ? ""
                : body.kind().trim().toLowerCase(Locale.ROOT);
        HelpKind job = KINDS.get(kind);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose what you want help with");
        }
        String label = job.label();
        String phone = StkPhoneNormalizer.normalize(body.phoneNumber());
        if (phone == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid phone number we can call");
        }
        String domain = blankToNull(body.domain());
        String note = blankToNull(body.note());

        StringBuilder message = new StringBuilder();
        message.append("Hire a developer\n");
        message.append(label);
        if (job.feeCents() != null) {
            message.append(" — KES 5,000");
        }
        message.append('\n');
        message.append("Call: ").append(phone);
        if (domain != null) {
            message.append("\nDomain: ").append(domain);
        }
        if (note != null) {
            message.append("\nNote: ").append(note);
        }
        supportService.sendTenantMessage(
                businessId,
                userId,
                new SendSupportMessageRequest(message.toString(), null, null, null)
        );
        eventPublisher.publishEvent(new DomainHelpRequestedEvent(
                businessId,
                label,
                phone,
                domain,
                note,
                job.feeCents()
        ));
        String confirmation = job.feeCents() == null
                ? "Request received. We'll call " + phone + " and send a text to that number."
                : "Request received. " + label + " is KES 5,000. We'll call " + phone
                        + " and send a text to that number.";
        return new RequestDomainHelpResponse(true, confirmation);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
