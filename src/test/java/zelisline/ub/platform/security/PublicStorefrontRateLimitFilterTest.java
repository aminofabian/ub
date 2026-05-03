package zelisline.ub.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PublicStorefrontRateLimitFilterTest {

    @Test
    void storefrontSlugFromPath_readsSlugBeforeNextSegment() {
        assertThat(PublicStorefrontRateLimitFilter.storefrontSlugFromPath(
                "/api/v1/public/businesses/acme-corp/storefront"))
                .isEqualTo("acme-corp");
        assertThat(PublicStorefrontRateLimitFilter.storefrontSlugFromPath(
                "/api/v1/public/businesses/x/catalog/items"))
                .isEqualTo("x");
    }

    @Test
    void storefrontSlugFromPath_emptyForNonPublic() {
        assertThat(PublicStorefrontRateLimitFilter.storefrontSlugFromPath("/api/v1/items")).isEmpty();
    }
}
