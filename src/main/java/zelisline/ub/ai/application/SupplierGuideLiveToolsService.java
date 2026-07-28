package zelisline.ub.ai.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import zelisline.ub.marketplace.api.dto.GlobalSupplierHubResponse;
import zelisline.ub.marketplace.api.dto.GlobalSupplierHubShopCard;
import zelisline.ub.marketplace.application.GlobalSupplierHubService;
import zelisline.ub.marketplace.application.SupplierPortalMessagesService;
import zelisline.ub.marketplace.api.dto.SupplierPortalMessageRow;

/** Supplier-portal-safe live facts for Guide (no shop P&L). */
@Service
@RequiredArgsConstructor
public class SupplierGuideLiveToolsService {

    private static final Logger log = LoggerFactory.getLogger(SupplierGuideLiveToolsService.class);

    private final GlobalSupplierHubService globalSupplierHubService;
    private final SupplierPortalMessagesService messagesService;

    public record LiveToolBundle(List<String> toolsUsed, String factsBlock) {}

    public LiveToolBundle gather(String marketplaceSupplierId, String surface, String skill, String userMessage) {
        List<String> used = new ArrayList<>();
        StringBuilder facts = new StringBuilder();
        String surf = surface == null ? "" : surface.toLowerCase(Locale.ROOT);
        String skillNorm = skill == null ? "" : skill.toLowerCase(Locale.ROOT);
        String msg = userMessage == null ? "" : userMessage.toLowerCase(Locale.ROOT);

        boolean wantShops = skillNorm.equals("morning_briefing")
                || surf.contains("supplier-portal")
                || mentions(msg, "owe", "balance", "shop", "paid", "morning", "briefing", "collect");
        boolean wantMessages = skillNorm.equals("draft_message")
                || surf.contains("message")
                || mentions(msg, "draft", "reply", "message", "sms", "whatsapp");

        if (wantShops) {
            try {
                GlobalSupplierHubResponse hub =
                        globalSupplierHubService.forMarketplaceSupplierId(marketplaceSupplierId);
                facts.append("Connected shops (AP projection):\n");
                if (hub.totals() != null) {
                    facts.append("- Total owed: ").append(money(hub.totals().owed())).append('\n');
                    facts.append("- Total paid: ").append(money(hub.totals().paid())).append('\n');
                }
                int n = 0;
                for (GlobalSupplierHubShopCard shop : hub.shops()) {
                    if (n++ >= 8) {
                        break;
                    }
                    facts.append("- ")
                            .append(nullToDash(shop.shopName()))
                            .append(": owed ")
                            .append(money(shop.owed()))
                            .append(", paid ")
                            .append(money(shop.paid()))
                            .append(", pending ")
                            .append(money(shop.pending()))
                            .append('\n');
                }
                used.add("getSupplierShopBalances");
            } catch (Exception ex) {
                log.debug("Supplier Guide shop tools failed: {}", ex.getMessage());
                facts.append("Note: shop balance data unavailable.\n");
            }
        }

        if (wantMessages) {
            try {
                List<SupplierPortalMessageRow> rows = messagesService.listForSupplier(marketplaceSupplierId);
                facts.append("Recent portal messages (up to 5):\n");
                if (rows.isEmpty()) {
                    facts.append("- (none)\n");
                } else {
                    int n = 0;
                    for (SupplierPortalMessageRow row : rows) {
                        if (n++ >= 5) {
                            break;
                        }
                        facts.append("- [")
                                .append(nullToDash(row.direction()))
                                .append("] ")
                                .append(nullToDash(row.shopName()))
                                .append(": ")
                                .append(truncate(row.body(), 120))
                                .append('\n');
                    }
                }
                used.add("listSupplierMessages");
            } catch (Exception ex) {
                log.debug("Supplier Guide message tools failed: {}", ex.getMessage());
                facts.append("Note: message preview unavailable.\n");
            }
        }

        if (used.isEmpty()) {
            return new LiveToolBundle(List.of(), "");
        }
        return new LiveToolBundle(List.copyOf(used), facts.toString());
    }

    private static boolean mentions(String msg, String... needles) {
        for (String n : needles) {
            if (msg.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static String money(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "—" : value.trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "—";
        }
        String t = value.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
