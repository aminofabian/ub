package zelisline.ub.till.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import zelisline.ub.till.api.dto.PublicTillAccessReviewResponse;
import zelisline.ub.till.application.TillAccessRequestService;

@RestController
@RequestMapping("/api/v1/public/tills")
@RequiredArgsConstructor
public class PublicTillAccessController {

    private final TillAccessRequestService tillAccessRequestService;

    @GetMapping("/review")
    public PublicTillAccessReviewResponse review(@RequestParam("token") String token) {
        return tillAccessRequestService.reviewByToken(requireToken(token));
    }

    @PostMapping("/approve")
    public PublicTillAccessReviewResponse approve(
            @RequestParam("token") String token,
            @Valid @RequestBody(required = false) PublicApproveBody body
    ) {
        String label = body != null ? body.label() : null;
        return tillAccessRequestService.approveByToken(requireToken(token), label);
    }

    @PostMapping("/dismiss")
    public PublicTillAccessReviewResponse dismiss(@RequestParam("token") String token) {
        return tillAccessRequestService.dismissByToken(requireToken(token));
    }

    private static String requireToken(String token) {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing till approval token");
        }
        return token.trim();
    }

    public record PublicApproveBody(@Size(max = 80) String label) {
    }
}
