package zelisline.ub.platform.media;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CloudinaryUrlParserTest {

    @Test
    void parsesDashboardStyleUrl() {
        var p = CloudinaryUrlParser.parse("cloudinary://k:s3cr3t@dzlv4lat4").orElseThrow();
        assertThat(p.apiKey()).isEqualTo("k");
        assertThat(p.apiSecret()).isEqualTo("s3cr3t");
        assertThat(p.cloudName()).isEqualTo("dzlv4lat4");
    }

    @Test
    void toleratesTrailingSlash() {
        var p = CloudinaryUrlParser.parse("cloudinary://a:b@mycloud/").orElseThrow();
        assertThat(p.cloudName()).isEqualTo("mycloud");
    }

    @Test
    void toleratesTrailingDotOnCloudName() {
        var p = CloudinaryUrlParser.parse("cloudinary://k:s3cr3t@dzlv4lat4.").orElseThrow();
        assertThat(p.cloudName()).isEqualTo("dzlv4lat4");
    }

    @Test
    void emptyReturnsEmpty() {
        assertThat(CloudinaryUrlParser.parse(null)).isEmpty();
        assertThat(CloudinaryUrlParser.parse("")).isEmpty();
        assertThat(CloudinaryUrlParser.parse("https://example.com")).isEmpty();
    }
}
