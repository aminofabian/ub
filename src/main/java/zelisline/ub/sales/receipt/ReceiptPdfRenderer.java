package zelisline.ub.sales.receipt;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

/**
 * Paperless / browser-download receipt. Layout mirrors the on-screen
 * {@code PosSaleReceipt} slip (80mm thermal form factor), not a generic A6 invoice.
 */
public final class ReceiptPdfRenderer {

    /** 80mm thermal roll width in PDF points (1 in = 72 pt). */
    private static final float PAGE_WIDTH_PT = 80f * 72f / 25.4f;
    private static final float MARGIN_LR = 8f;
    private static final float MARGIN_TB = 10f;

    private static final Map<String, String> PAYMENT_LABELS = Map.of(
            "cash", "Cash",
            "mpesa_manual", "M-Pesa",
            "card", "Card",
            "customer_credit", "Customer tab",
            "customer_wallet", "Wallet",
            "loyalty_redeem", "Loyalty"
    );

    private ReceiptPdfRenderer() {
    }

    public static byte[] render(ReceiptSnapshot s) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Rectangle page = new Rectangle(PAGE_WIDTH_PT, estimatePageHeight(s));
            Document doc = new Document(page, MARGIN_LR, MARGIN_LR, MARGIN_TB, MARGIN_TB);
            PdfWriter.getInstance(doc, baos);
            doc.open();

            Font shop = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);
            Font location = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
            Font locationBranch = FontFactory.getFont(FontFactory.HELVETICA, 8);
            Font saleId = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f);
            Font body = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f);
            Font bodyRegular = FontFactory.getFont(FontFactory.HELVETICA, 9);
            Font colHead = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.5f);
            Font lineItem = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f);
            Font totalLabel = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);
            Font money = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f);
            Font contact = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
            Font closing = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
            Font voided = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);

            if (isVoided(s)) {
                addCentered(doc, "VOIDED", voided);
                addSpacer(doc, 4f);
            }

            boolean hasLogo = addLogoIfPresent(doc, s.logoUrl());
            String business = strip(s.businessName());
            String branch = strip(s.branchName());
            if (!hasLogo && !business.isEmpty()) {
                addCentered(doc, business, shop);
            }
            String locationText = !branch.isEmpty() ? branch : (!business.isEmpty() ? business : null);
            if (locationText != null) {
                Font locFont = hasLogo ? locationBranch : location;
                addCentered(doc, locationText, locFont);
            }

            addRule(doc, false);
            addLeft(doc, saleHeading(s).toUpperCase(Locale.ROOT), saleId);

            if (s.servedByName() != null && !s.servedByName().isBlank()) {
                addPair(doc, strip(s.soldAtDisplay()), "Cashier: " + strip(s.servedByName()), bodyRegular, body);
            } else if (s.soldAtDisplay() != null && !s.soldAtDisplay().isBlank()) {
                addLeft(doc, strip(s.soldAtDisplay()), body);
            }
            if (s.customerName() != null && !s.customerName().isBlank()) {
                addLeft(doc, "Customer: " + strip(s.customerName()), body);
            }

            addRule(doc, false);
            addLineItems(doc, s, colHead, lineItem);
            addRule(doc, false);

            String paidVia = paidViaNote(s);
            if (paidVia != null) {
                addLeft(doc, paidVia, body);
                addSpacer(doc, 3f);
            }

            addRule(doc, false);
            addMoneyRow(doc, "TOTAL", moneyDisplay(s.grandTotalDisplay(), s.currency()), totalLabel, totalLabel);
            if (s.cashReceivedDisplay() != null && !s.cashReceivedDisplay().isBlank()) {
                addMoneyRow(doc, "Received", moneyDisplay(s.cashReceivedDisplay(), s.currency()), money, money);
                addMoneyRow(
                        doc,
                        "Change",
                        moneyDisplay(
                                s.changeGivenDisplay() != null && !s.changeGivenDisplay().isBlank()
                                        ? s.changeGivenDisplay()
                                        : "0.00",
                                s.currency()),
                        money,
                        money);
            }

            if (hasContact(s)) {
                addRule(doc, true);
                addCenteredIfPresent(doc, s.branchAddress(), contact);
                addCenteredIfPresent(doc, formatContactLine("Tel", s.branchPhone()), contact);
                addCenteredIfPresent(doc, formatContactLine("M-Pesa Till", s.tillNumber()), contact);
                addCenteredIfPresent(doc, s.branchEmail(), contact);
                addCenteredIfPresent(doc, s.branchWebsite(), contact);
            }

            addSpacer(doc, 6f);
            addCentered(doc, closingMessage(s), closing);

            doc.close();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render receipt PDF", e);
        }
    }

    private static void addLineItems(Document doc, ReceiptSnapshot s, Font head, Font body)
            throws DocumentException {
        PdfPTable table = new PdfPTable(3);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{3.2f, 1.1f, 1.7f});
        table.setSpacingBefore(0);
        table.setSpacingAfter(0);

        table.addCell(headerCell("Item", head, Element.ALIGN_LEFT));
        table.addCell(headerCell("Qty", head, Element.ALIGN_RIGHT));
        table.addCell(headerCell("Price", head, Element.ALIGN_RIGHT));

        for (ReceiptLineRow line : s.lines()) {
            table.addCell(bodyCell(strip(line.description()), body, Element.ALIGN_LEFT));
            table.addCell(bodyCell(strip(line.quantity()), body, Element.ALIGN_RIGHT));
            table.addCell(bodyCell(strip(line.unitPrice()), body, Element.ALIGN_RIGHT));
        }
        doc.add(table);
    }

    private static PdfPCell headerCell(String text, Font font, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderWidthBottom(0.8f);
        c.setBorderColor(Color.BLACK);
        c.setPaddingTop(1f);
        c.setPaddingBottom(3f);
        c.setPaddingLeft(0);
        c.setPaddingRight(0);
        c.setHorizontalAlignment(align);
        c.setVerticalAlignment(Element.ALIGN_BOTTOM);
        return c;
    }

    private static PdfPCell bodyCell(String text, Font font, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, font));
        c.setBorder(Rectangle.NO_BORDER);
        c.setPaddingTop(3f);
        c.setPaddingBottom(2f);
        c.setPaddingLeft(0);
        c.setPaddingRight(0);
        c.setHorizontalAlignment(align);
        c.setVerticalAlignment(Element.ALIGN_TOP);
        return c;
    }

    private static void addMoneyRow(Document doc, String label, String value, Font labelFont, Font valueFont)
            throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{1.2f, 1f});
        table.setSpacingBefore(1.5f);
        table.setSpacingAfter(0);

        PdfPCell left = new PdfPCell(new Phrase(label, labelFont));
        left.setBorder(Rectangle.NO_BORDER);
        left.setPadding(0);
        left.setHorizontalAlignment(Element.ALIGN_LEFT);

        PdfPCell right = new PdfPCell(new Phrase(value, valueFont));
        right.setBorder(Rectangle.NO_BORDER);
        right.setPadding(0);
        right.setHorizontalAlignment(Element.ALIGN_RIGHT);

        table.addCell(left);
        table.addCell(right);
        doc.add(table);
    }

    private static void addPair(Document doc, String left, String right, Font leftFont, Font rightFont)
            throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{1.2f, 1f});
        table.setSpacingBefore(1f);
        table.setSpacingAfter(0);

        PdfPCell l = new PdfPCell(new Phrase(left, leftFont));
        l.setBorder(Rectangle.NO_BORDER);
        l.setPadding(0);
        l.setHorizontalAlignment(Element.ALIGN_LEFT);

        PdfPCell r = new PdfPCell(new Phrase(right, rightFont));
        r.setBorder(Rectangle.NO_BORDER);
        r.setPadding(0);
        r.setHorizontalAlignment(Element.ALIGN_RIGHT);

        table.addCell(l);
        table.addCell(r);
        doc.add(table);
    }

    private static void addRule(Document doc, boolean dashed) throws DocumentException {
        addSpacer(doc, 3f);
        if (dashed) {
            Paragraph p = new Paragraph(repeat('-', 42), FontFactory.getFont(FontFactory.HELVETICA, 7));
            p.setAlignment(Element.ALIGN_CENTER);
            p.setSpacingBefore(0);
            p.setSpacingAfter(0);
            p.setLeading(8f);
            doc.add(p);
        } else {
            PdfPTable table = new PdfPTable(1);
            table.setWidthPercentage(100);
            PdfPCell cell = new PdfPCell();
            cell.setBorder(Rectangle.TOP);
            cell.setBorderWidthTop(0.85f);
            cell.setBorderColor(Color.BLACK);
            cell.setFixedHeight(1f);
            cell.setPadding(0);
            table.addCell(cell);
            doc.add(table);
        }
        addSpacer(doc, 3f);
    }

    private static boolean addLogoIfPresent(Document doc, String logoUrl) {
        if (logoUrl == null || logoUrl.isBlank()) {
            return false;
        }
        try {
            Image img = Image.getInstance(URI.create(logoUrl.trim()).toURL());
            img.scaleToFit(PAGE_WIDTH_PT - MARGIN_LR * 2f, 31f);
            img.setAlignment(Element.ALIGN_CENTER);
            doc.add(img);
            addSpacer(doc, 4f);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void addCentered(Document doc, String text, Font font) throws DocumentException {
        Paragraph p = new Paragraph(text, font);
        p.setAlignment(Element.ALIGN_CENTER);
        p.setSpacingBefore(0);
        p.setSpacingAfter(1.5f);
        p.setLeading(font.getSize() * 1.25f);
        doc.add(p);
    }

    private static void addLeft(Document doc, String text, Font font) throws DocumentException {
        Paragraph p = new Paragraph(text, font);
        p.setAlignment(Element.ALIGN_LEFT);
        p.setSpacingBefore(0);
        p.setSpacingAfter(1.5f);
        p.setLeading(font.getSize() * 1.3f);
        doc.add(p);
    }

    private static void addCenteredIfPresent(Document doc, String text, Font font) throws DocumentException {
        if (text == null || text.isBlank()) {
            return;
        }
        addCentered(doc, strip(text), font);
    }

    private static void addSpacer(Document doc, float pt) throws DocumentException {
        Paragraph p = new Paragraph(" ");
        p.setLeading(pt);
        p.setSpacingBefore(0);
        p.setSpacingAfter(0);
        doc.add(p);
    }

    private static boolean hasContact(ReceiptSnapshot s) {
        return notBlank(s.branchAddress())
                || notBlank(s.branchPhone())
                || notBlank(s.tillNumber())
                || notBlank(s.branchEmail())
                || notBlank(s.branchWebsite());
    }

    private static boolean isVoided(ReceiptSnapshot s) {
        if (s.saleStatus() != null && "voided".equalsIgnoreCase(s.saleStatus().trim())) {
            return true;
        }
        String note = s.footerNote();
        return note != null && note.toUpperCase(Locale.ROOT).contains("VOIDED");
    }

    private static String closingMessage(ReceiptSnapshot s) {
        if (s.branchReceiptMessage() != null && !s.branchReceiptMessage().isBlank()) {
            return strip(s.branchReceiptMessage());
        }
        return "Thank you";
    }

    private static String saleHeading(ReceiptSnapshot s) {
        if (s.explicitReceiptLabel() != null && !s.explicitReceiptLabel().isBlank()) {
            return s.explicitReceiptLabel().trim();
        }
        if (s.receiptNo() != null) {
            return "Receipt #" + s.receiptNo();
        }
        String id = s.saleId() == null ? "" : s.saleId().trim();
        String shortId = id.length() > 8 ? id.substring(0, 8) : id;
        return "#" + shortId.toUpperCase(Locale.ROOT);
    }

    private static String paidViaNote(ReceiptSnapshot s) {
        if (s.payments() == null || s.payments().isEmpty()) {
            return null;
        }
        if (s.payments().size() == 1) {
            // Match on-screen slip: single tender shows label only (no reference).
            return "Paid via: " + paymentMethodLabel(s.payments().get(0).method());
        }
        String joined = s.payments().stream()
                .map(p -> {
                    String label = paymentMethodLabel(p.method());
                    if (p.reference() != null && !p.reference().isBlank()) {
                        return label + " (" + p.reference().trim() + ")";
                    }
                    return label;
                })
                .collect(Collectors.joining(" + "));
        return "Paid via: " + joined;
    }

    private static String paymentMethodLabel(String method) {
        String raw = method == null ? "" : method.trim();
        String key = raw.toLowerCase(Locale.ROOT);
        return PAYMENT_LABELS.getOrDefault(key, raw.replace('_', ' '));
    }

    private static String moneyDisplay(String amount, String currency) {
        String amt = amount == null ? "" : amount.trim();
        String cur = currency == null ? "" : currency.trim();
        if (cur.isEmpty()) {
            return amt;
        }
        return (amt + " " + cur).trim();
    }

    private static String formatContactLine(String label, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return label + ": " + value.trim();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String strip(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(Math.max(0, n));
    }

    /**
     * Rough content-based height so the download looks like a short thermal slip
     * rather than an A4-tall blank page.
     */
    private static float estimatePageHeight(ReceiptSnapshot s) {
        int lines = s.lines() == null ? 0 : s.lines().size();
        float h = MARGIN_TB * 2f;
        h += 44f; // brand / logo
        h += 64f; // meta + rules
        h += 20f + lines * 18f; // item header + rows (allow wrap)
        h += 32f; // paid via + rule
        h += 44f; // totals
        if (hasContact(s)) {
            h += 64f;
        }
        h += 28f; // closing
        if (isVoided(s)) {
            h += 18f;
        }
        // Headroom so wrapped lines don't spill onto a second blank-looking page.
        h *= 1.15f;
        return Math.max(200f, Math.min(h, 297f * 72f / 25.4f));
    }
}
