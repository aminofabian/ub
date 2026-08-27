package zelisline.ub.sales.api;

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
import zelisline.ub.sales.api.dto.DrawoutResponse;
import zelisline.ub.sales.api.dto.PublicDrawoutReviewResponse;
import zelisline.ub.sales.application.DrawoutService;

@RestController
@RequestMapping("/api/v1/public/drawouts")
@RequiredArgsConstructor
public class PublicDrawoutApprovalController {

    private final DrawoutService drawoutService;

    @GetMapping("/review")
    public PublicDrawoutReviewResponse review(@RequestParam("token") String token) {
        return drawoutService.reviewByToken(requireToken(token));
    }

    @PostMapping("/approve")
    public DrawoutResponse approve(@RequestParam("token") String token) {
        return drawoutService.approveByToken(requireToken(token));
    }

    @PostMapping("/reject")
    public DrawoutResponse reject(
            @RequestParam("token") String token,
            @Valid @RequestBody(required = false) PublicRejectBody body
    ) {
        String reason = body != null ? body.reason() : null;
        return drawoutService.rejectByToken(requireToken(token), reason);
    }

    private static String requireToken(String token) {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing approval token");
        }
        return token.trim();
    }

    public record PublicRejectBody(@Size(max = 500) String reason) {
    }
}
