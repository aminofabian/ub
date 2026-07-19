package zelisline.ub.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import zelisline.ub.tenancy.api.dto.PublicHostResolveResponse;
import zelisline.ub.tenancy.api.dto.TenantAuthConfigDto;
import zelisline.ub.tenancy.api.dto.TenantBrandingDto;
import zelisline.ub.tenancy.api.dto.TenantPasswordPolicyDto;

class EmailVerificationBrandingContextTest {

    @Test
    void fromHostWithoutTenantUsesSubdomainSlug() {
        var ctx = EmailVerificationBrandingContext.fromHost(Optional.empty(), "uzapoint.kiosk.ke");
        assertThat(ctx.slug()).isEqualTo("uzapoint");
        assertThat(ctx.displayName()).isEqualTo("Uzapoint");
        assertThat(ctx.host()).isEqualTo("uzapoint.kiosk.ke");
        assertThat(ctx.tagline()).contains("point of sale");
    }

    @Test
    void fromTenantPrefersStoredBranding() {
        var tenant = new PublicHostResolveResponse(
                "id",
                "Uza Point Ltd",
                "uzapoint",
                "ACTIVE",
                new TenantBrandingDto(
                        "UzaPoint POS",
                        "https://cdn.example/logo.png",
                        null,
                        "#111827",
                        "#F97316",
                        null,
                        null,
                        null,
                        null,
                        null),
                new TenantAuthConfigDto(
                        java.util.List.of("password"),
                        java.util.List.of(),
                        new TenantPasswordPolicyDto(8, false, false)),
                java.util.Map.of(),
                true,
                java.time.Instant.now(),
                "KE",
                java.util.List.of());
        var ctx = EmailVerificationBrandingContext.fromHost(Optional.of(tenant), "uzapoint.kiosk.ke");
        assertThat(ctx.displayName()).isEqualTo("UzaPoint POS");
        assertThat(ctx.primaryColor()).isEqualTo("#111827");
        assertThat(ctx.logoUrl()).isEqualTo("https://cdn.example/logo.png");
    }
}
