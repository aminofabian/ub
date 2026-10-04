package zelisline.ub.platform.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class AttachmentFormatTest {

    private static final byte[] OLE = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0};
    private static final byte[] ZIP = {'P', 'K', 3, 4, 20, 0};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10};

    private static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void recognisesImagesAndPdfByContentWhateverTheName() {
        assertThat(AttachmentFormat.detect(PNG, "photo.pdf")).contains(AttachmentFormat.PNG);
        assertThat(AttachmentFormat.detect(text("%PDF-1.7"), null)).contains(AttachmentFormat.PDF);
    }

    @Test
    void usesTheExtensionToTellOfficeContainersApart() {
        assertThat(AttachmentFormat.detect(OLE, "stock.XLS")).contains(AttachmentFormat.XLS);
        assertThat(AttachmentFormat.detect(OLE, "letter.doc")).contains(AttachmentFormat.DOC);
        assertThat(AttachmentFormat.detect(ZIP, "stock.xlsx")).contains(AttachmentFormat.XLSX);
        assertThat(AttachmentFormat.detect(ZIP, "letter.docx")).contains(AttachmentFormat.DOCX);
        assertThat(AttachmentFormat.detect(ZIP, "archive.zip")).isEmpty();
        assertThat(AttachmentFormat.detect(OLE, "unnamed")).isEmpty();
    }

    @Test
    void acceptsUtf8TextOnlyAsCsvOrTxt() {
        assertThat(AttachmentFormat.detect(text("sku,qty\nA,1\n"), "items.csv")).contains(AttachmentFormat.CSV);
        assertThat(AttachmentFormat.detect(text("Habari 👋"), "note.txt")).contains(AttachmentFormat.TXT);
        assertThat(AttachmentFormat.detect(text("sku,qty"), "items.html")).isEmpty();
    }

    @Test
    void refusesActiveContentAndBinaryNoise() {
        assertThat(AttachmentFormat.detect(text("<svg xmlns='http://www.w3.org/2000/svg'/>"), "logo.svg")).isEmpty();
        assertThat(AttachmentFormat.detect(new byte[] {1, 0, 2, 3}, "data.csv")).isEmpty();
        assertThat(AttachmentFormat.detect(new byte[] {(byte) 0xC3, (byte) 0x28}, "bad.txt")).isEmpty();
        assertThat(AttachmentFormat.detect(new byte[0], "empty.txt")).isEmpty();
    }

    @Test
    void toleratesAMultiByteCharacterCutByTheSniffWindow() {
        byte[] bytes = new byte[8192 + 10];
        Arrays.fill(bytes, (byte) 'a');
        byte[] euro = "€".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(euro, 0, bytes, 8191, euro.length);

        assertThat(AttachmentFormat.detect(bytes, "long.txt")).contains(AttachmentFormat.TXT);
    }
}
