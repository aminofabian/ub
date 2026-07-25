package zelisline.ub.messages.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PublicContactMessageRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Email @Size(max = 255) String email,
        @Size(max = 32) String phone,
        @NotBlank @Size(max = 4000) String message,
        @Size(max = 512) String sourcePath,
        /** Till CAPTCHA kind: TOTAL, CHANGE, DISCOUNT, MULTIPLY, MISSING, VAT, INVENTORY */
        @NotBlank @Size(max = 32) String challengeKind,
        @Valid @Size(max = 4) List<ContactChallengeLine> lines,
        @Min(0) @Max(20000) Integer tendered,
        @Min(1) @Max(50) Integer percent,
        @Min(0) @Max(20000) Integer baseAmount,
        @Min(0) @Max(20000) Integer secondaryAmount,
        @NotNull Integer challengeAnswer,
        /** Honeypot — real users leave this empty; bots often fill it. */
        @Size(max = 120) String website
) {}
