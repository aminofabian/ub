package zelisline.ub.platform.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class CloudinaryDotEnvSupportTest {

    @Test
    void valueForKeyReadsUnquotedLine() {
        String v = CloudinaryDotEnvSupport.valueForKey(
                List.of("CLOUDINARY_URL=cloudinary://a:b@c", "OTHER=1"),
                "CLOUDINARY_URL");
        assertThat(v).isEqualTo("cloudinary://a:b@c");
    }

    @Test
    void valueForKeyStripsDoubleQuotes() {
        String v = CloudinaryDotEnvSupport.valueForKey(
                List.of("CLOUDINARY_URL=\"cloudinary://a:b@c\""),
                "CLOUDINARY_URL");
        assertThat(v).isEqualTo("cloudinary://a:b@c");
    }

    @Test
    void valueForKeySkipsCommentsAndBlank() {
        String v = CloudinaryDotEnvSupport.valueForKey(
                List.of("", " # x", "CLOUDINARY_URL=cloudinary://k:sec@n"),
                "CLOUDINARY_URL");
        assertThat(v).isEqualTo("cloudinary://k:sec@n");
    }

    @Test
    void valueForKeyUsesFirstEqualsOnlyForValue() {
        String v = CloudinaryDotEnvSupport.valueForKey(
                List.of("CLOUDINARY_URL=cloudinary://key:sec=ret@cloud"),
                "CLOUDINARY_URL");
        assertThat(v).isEqualTo("cloudinary://key:sec=ret@cloud");
    }
}
