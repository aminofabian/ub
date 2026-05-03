package zelisline.ub.sales.receipt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ReceiptEscPosRenderer {

    private static final byte[] INIT = new byte[]{0x1B, 0x40};
    private static final byte[] CUT = new byte[]{0x1D, 0x56, 0x00};

    private ReceiptEscPosRenderer() {
    }

    public static byte[] render(ReceiptSnapshot s, int widthMm) {
        int w = charWidth(widthMm);
        List<String> out = new ArrayList<>();
        out.add(center(strip(s.businessName()), w));
        out.add(center(strip(s.branchName()), w));
        out.add(repeat('-', w));
        out.add(center("Sale " + s.saleId(), w));
        out.add(center(strip(s.soldAtDisplay()) + " | " + strip(s.saleStatus()), w));
        out.add(repeat('-', w));
        for (ReceiptLineRow line : s.lines()) {
            for (String row : wrap(strip(line.description()), w)) {
                out.add(row);
            }
            String amt = strip(line.quantity()) + " x " + strip(line.unitPrice()) + " = " + strip(line.lineTotal());
            out.add(padLeft(amt, w));
        }
        out.add(repeat('-', w));
        for (ReceiptPaymentRow p : s.payments()) {
            String ref = p.reference() != null && !p.reference().isBlank() ? " " + strip(p.reference()) : "";
            out.add(strip(p.method()) + ref);
            out.add(padLeft(strip(p.amount()), w));
        }
        out.add(repeat('-', w));
        out.add(padLeft("TOTAL " + strip(s.grandTotalDisplay()) + " " + strip(s.currency()), w));
        if (s.footerNote() != null && !s.footerNote().isBlank()) {
            out.add(center(strip(s.footerNote()), w));
        }
        out.add(center("Thank you", w));
        out.add("");

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(INIT);
            for (String line : out) {
                baos.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
            }
            baos.write(CUT);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to render ESC/POS receipt", e);
        }
    }

    static int charWidth(int widthMm) {
        if (widthMm <= 58) {
            return 32;
        }
        return 48;
    }

    private static String center(String text, int w) {
        if (text.length() >= w) {
            return text.substring(0, w);
        }
        int pad = (w - text.length()) / 2;
        return " ".repeat(pad) + text;
    }

    private static String padLeft(String text, int w) {
        if (text.length() >= w) {
            return text.substring(text.length() - w);
        }
        return " ".repeat(w - text.length()) + text;
    }

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    private static List<String> wrap(String text, int w) {
        List<String> rows = new ArrayList<>();
        if (text.isEmpty()) {
            rows.add("");
            return rows;
        }
        int i = 0;
        while (i < text.length()) {
            int end = Math.min(i + w, text.length());
            rows.add(text.substring(i, end));
            i = end;
        }
        return rows;
    }

    private static String strip(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            b.append(c >= 32 && c < 127 ? c : '?');
        }
        return b.toString();
    }
}
