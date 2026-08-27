package zelisline.ub.onboarding.sequence.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import zelisline.ub.onboarding.sequence.application.MerchantOnboardingSequenceService;

/**
 * Public mute link from onboarding tip emails (signed token, no login).
 */
@RestController
@RequestMapping("/api/v1/public/onboarding-sequence")
@RequiredArgsConstructor
public class PublicOnboardingSequenceController {

    private final MerchantOnboardingSequenceService sequenceService;

    @GetMapping(value = "/mute", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> mute(@RequestParam("token") String token) {
        boolean ok = sequenceService.muteWithToken(token);
        if (!ok) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("""
                    <!DOCTYPE html><html><body style="font-family:sans-serif;padding:2rem">
                    <h1>Link expired</h1>
                    <p>This mute link is invalid or expired. You can mute tips from your Kiosk hub while signed in.</p>
                    </body></html>
                    """);
        }
        return ResponseEntity.ok("""
                <!DOCTYPE html><html><body style="font-family:sans-serif;padding:2rem">
                <h1>Tips muted</h1>
                <p>You won't get more onboarding tip emails from Kiosk for this shop. Support is still here if you need us — 0714 282 874.</p>
                </body></html>
                """);
    }
}
