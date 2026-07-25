package zelisline.ub.messages.api;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.messages.api.dto.PublicContactMessageRequest;
import zelisline.ub.messages.api.dto.PublicContactMessageResponse;
import zelisline.ub.messages.application.ContactMessageService;

@Validated
@RestController
@RequiredArgsConstructor
public class PublicContactMessagesController {

    private final ContactMessageService contactMessageService;

    @PostMapping("/api/v1/public/contact-messages")
    public ResponseEntity<PublicContactMessageResponse> submitPlatform(
            @Valid @RequestBody PublicContactMessageRequest body,
            HttpServletRequest request
    ) {
        PublicContactMessageResponse out = contactMessageService.submitPlatform(body, request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(out);
    }

    @PostMapping("/api/v1/public/businesses/{slug}/contact-messages")
    public ResponseEntity<PublicContactMessageResponse> submitTenant(
            @PathVariable String slug,
            @Valid @RequestBody PublicContactMessageRequest body,
            HttpServletRequest request
    ) {
        PublicContactMessageResponse out = contactMessageService.submitTenant(slug, body, request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(out);
    }
}
