package zelisline.ub.platform.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class MediaFormatTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void detectsRasterFormatsByMagicNumber() {
        assertThat(MediaFormat.detect(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A))).contains(MediaFormat.PNG);
        assertThat(MediaFormat.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0))).contains(MediaFormat.JPEG);
        assertThat(MediaFormat.detect(ascii("GIF89a"))).contains(MediaFormat.GIF);
        assertThat(MediaFormat.detect(ascii("RIFF\0\0\0\0WEBPVP8 "))).contains(MediaFormat.WEBP);
        assertThat(MediaFormat.detect(bytes(0x00, 0x00, 0x01, 0x00, 0x01))).contains(MediaFormat.ICO);
        assertThat(MediaFormat.detect(ascii("BM\0\0\0\0"))).contains(MediaFormat.BMP);
    }

    @Test
    void detectsIsoMediaBrands() {
        assertThat(MediaFormat.detect(ascii("\0\0\0\u0018ftypavif"))).contains(MediaFormat.AVIF);
        assertThat(MediaFormat.detect(ascii("\0\0\0\u0018ftypheic"))).contains(MediaFormat.HEIC);
        assertThat(MediaFormat.detect(ascii("\0\0\0\u0018ftypisom"))).isEmpty();
    }

    @Test
    void detectsPdfAndSvgDocuments() {
        assertThat(MediaFormat.detect(ascii("%PDF-1.7"))).contains(MediaFormat.PDF);
        assertThat(MediaFormat.detect(ascii("<?xml version=\"1.0\"?>\n<svg xmlns=\"x\"/>"))).contains(MediaFormat.SVG);
        assertThat(MediaFormat.detect("\uFEFF  <svg viewBox=\"0 0 1 1\"/>".getBytes(StandardCharsets.UTF_8)))
                .contains(MediaFormat.SVG);
    }

    @Test
    void rejectsUnknownOrTinyContent() {
        assertThat(MediaFormat.detect(ascii("<html><body>hi</body></html>"))).isEmpty();
        assertThat(MediaFormat.detect(ascii("plain text file"))).isEmpty();
        assertThat(MediaFormat.detect(bytes(0x89))).isEmpty();
        assertThat(MediaFormat.detect(null)).isEmpty();
    }

    @Test
    void exposesExtensionAndContentType() {
        assertThat(MediaFormat.JPEG.extension()).isEqualTo("jpg");
        assertThat(MediaFormat.SVG.contentType()).isEqualTo("image/svg+xml");
    }
}
