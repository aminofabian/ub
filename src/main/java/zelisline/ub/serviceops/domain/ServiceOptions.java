package zelisline.ub.serviceops.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What a customer can choose on a service (ADR 0025): groups such as "Hair length" where one
 * option is picked, and groups of extras where several may be. An option adds to the price and
 * to the time the service takes. The groups are kept on the service as JSON; what was chosen is
 * kept on the visit line and the sale line as a snapshot, so renaming or removing an option
 * later never changes what was sold.
 */
public final class ServiceOptions {

    public static final int MAX_GROUPS = 6;
    public static final int MAX_OPTIONS_PER_GROUP = 12;
    /** A line's snapshot column holds 2,000 characters; ten choices always fit. */
    public static final int MAX_CHOSEN = 10;
    public static final int MAX_NAME = 40;
    public static final int MAX_EXTRA_MINUTES = 600;
    private static final int SNAPSHOT_LENGTH = 2000;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Group>> GROUPS = new TypeReference<>() { };
    private static final TypeReference<List<Chosen>> CHOSEN = new TypeReference<>() { };

    private ServiceOptions() {
    }

    /**
     * @param required a visit cannot be paid until something in this group is chosen
     * @param multiple several options may be chosen (extras); otherwise exactly one (a variant)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Group(String id, String name, boolean required, boolean multiple, List<Option> options) {
        public Group {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Option(String id, String name, BigDecimal priceDelta, int extraMinutes) {
        public Option {
            priceDelta = priceDelta == null ? BigDecimal.ZERO : priceDelta;
        }
    }

    /** One option as it was chosen: names and amounts copied, not looked up again. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Chosen(String group, String option, BigDecimal priceDelta, String optionId, int extraMinutes) {
        public Chosen {
            priceDelta = priceDelta == null ? BigDecimal.ZERO : priceDelta;
        }
    }

    /** Unreadable or empty text reads as no options, so one bad row cannot break the till. */
    public static List<Group> decode(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, GROUPS);
        } catch (Exception e) {
            return List.of();
        }
    }

    public static String encode(List<Group> groups) {
        if (groups == null || groups.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(groups);
        } catch (Exception e) {
            throw new IllegalStateException("Could not store the options", e);
        }
    }

    public static List<Chosen> decodeChosen(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, CHOSEN);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** @throws IllegalArgumentException when the choices do not fit on a line */
    public static String encodeChosen(List<Chosen> chosen) {
        if (chosen == null || chosen.isEmpty()) {
            return null;
        }
        String json;
        try {
            json = MAPPER.writeValueAsString(chosen);
        } catch (Exception e) {
            throw new IllegalStateException("Could not store the chosen options", e);
        }
        if (json.length() > SNAPSHOT_LENGTH) {
            throw new IllegalArgumentException("Too many options chosen for one service");
        }
        return json;
    }

    /**
     * The options named by {@code optionIds}, in the order the service lists them.
     *
     * @throws IllegalArgumentException with a message for the till when an id is not an option of
     *         this service, or two are chosen where only one is allowed
     */
    public static List<Chosen> choose(List<Group> groups, Collection<String> optionIds) {
        Set<String> wanted = wanted(optionIds);
        if (wanted.isEmpty()) {
            return List.of();
        }
        if (wanted.size() > MAX_CHOSEN) {
            throw new IllegalArgumentException("Choose at most " + MAX_CHOSEN + " options for one service");
        }
        List<Chosen> out = pick(groups, wanted, true);
        if (out.size() != wanted.size()) {
            throw new IllegalArgumentException("One of those options is no longer offered");
        }
        return out;
    }

    /** As {@link #choose}, but an option that is no longer offered is quietly left out. */
    public static List<Chosen> chooseStillOffered(List<Group> groups, Collection<String> optionIds) {
        Set<String> wanted = wanted(optionIds);
        return wanted.isEmpty() ? List.of() : pick(groups, wanted, false);
    }

    private static List<Chosen> pick(List<Group> groups, Set<String> wanted, boolean strict) {
        List<Chosen> out = new ArrayList<>();
        for (Group group : groups) {
            int inGroup = 0;
            for (Option option : group.options()) {
                if (!wanted.contains(option.id())) {
                    continue;
                }
                if (!group.multiple() && inGroup > 0) {
                    if (strict) {
                        throw new IllegalArgumentException("Choose only one for " + group.name());
                    }
                    continue;
                }
                inGroup++;
                out.add(new Chosen(group.name(), option.name(),
                        option.priceDelta().setScale(2, RoundingMode.HALF_UP), option.id(), option.extraMinutes()));
            }
        }
        return out;
    }

    private static Set<String> wanted(Collection<String> optionIds) {
        Set<String> wanted = new LinkedHashSet<>();
        if (optionIds != null) {
            for (String id : optionIds) {
                if (id != null && !id.isBlank()) {
                    wanted.add(id.trim());
                }
            }
        }
        return wanted;
    }

    /** Name of the first group that must be chosen from and was not; null when nothing is missing. */
    public static String firstMissing(List<Group> groups, List<Chosen> chosen) {
        Set<String> chosenIds = new HashSet<>();
        for (Chosen c : chosen) {
            chosenIds.add(c.optionId());
        }
        for (Group group : groups) {
            if (group.required() && group.options().stream().noneMatch(o -> chosenIds.contains(o.id()))) {
                return group.name();
            }
        }
        return null;
    }

    public static BigDecimal extraPrice(List<Chosen> chosen) {
        BigDecimal total = BigDecimal.ZERO;
        for (Chosen c : chosen) {
            total = total.add(c.priceDelta());
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    public static int extraMinutes(List<Chosen> chosen) {
        int total = 0;
        for (Chosen c : chosen) {
            total += Math.max(0, c.extraMinutes());
        }
        return total;
    }

    /** Extra minutes of a stored snapshot; zero for a line without options or a restaurant line. */
    public static int extraMinutes(String chosenJson) {
        return extraMinutes(decodeChosen(chosenJson));
    }

    public static List<String> ids(List<Chosen> chosen) {
        return chosen.stream().map(Chosen::optionId).toList();
    }

    /** "Braids (Long, Extra packs)" for a receipt; the plain name when nothing was chosen. */
    public static String label(String serviceName, List<Chosen> chosen) {
        if (chosen.isEmpty()) {
            return serviceName;
        }
        return serviceName + " (" + String.join(", ", chosen.stream().map(Chosen::option).toList()) + ")";
    }
}
