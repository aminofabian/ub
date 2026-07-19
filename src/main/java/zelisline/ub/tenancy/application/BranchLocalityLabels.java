package zelisline.ub.tenancy.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import zelisline.ub.tenancy.api.dto.OnboardingAnswersDto;
import zelisline.ub.tenancy.api.dto.OnboardingSettingsResponse;
import zelisline.ub.tenancy.domain.Branch;

/**
 * Builds short shopper-facing locality labels for SEO and marketplace listings
 * from onboarding answers and active branch name/address fields.
 */
public final class BranchLocalityLabels {

    private BranchLocalityLabels() {}

    public static List<String> fromOnboardingAndBranches(
            OnboardingSettingsResponse onboarding,
            List<Branch> branches) {
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        if (onboarding != null) {
            OnboardingAnswersDto answers = onboarding.answers();
            if (answers != null && answers.branchLocalities() != null) {
                for (String locality : answers.branchLocalities()) {
                    String cleaned = cleanLocationLabel(locality);
                    if (cleaned != null) {
                        labels.add(cleaned);
                    }
                }
            }
        }
        if (branches != null) {
            for (Branch branch : branches) {
                if (branch == null || !branch.isActive()) {
                    continue;
                }
                String fromAddress = cleanLocationLabel(branch.getAddress());
                if (fromAddress != null) {
                    labels.add(fromAddress);
                    continue;
                }
                String fromName = localityFromBranchName(branch.getName());
                if (fromName != null) {
                    labels.add(fromName);
                }
            }
        }
        return List.copyOf(labels);
    }

    /** Primary area for titles — first label, or null when unknown. */
    public static String primary(List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return null;
        }
        return labels.get(0);
    }

    static String localityFromBranchName(String name) {
        String cleaned = blankToNull(name);
        if (cleaned == null) {
            return null;
        }
        String withoutSuffix = cleaned.replaceAll("(?i)\\s+branch$", "").trim();
        return cleanLocationLabel(withoutSuffix);
    }

    static String cleanLocationLabel(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        // Prefer a short locality when address is a long free-text line.
        if (value.contains(",")) {
            String first = value.split(",", 2)[0].trim();
            if (!first.isBlank() && first.length() <= 48) {
                value = first;
            }
        }
        if (value.length() > 64) {
            value = value.substring(0, 64).trim();
        }
        return value.isBlank() ? null : value;
    }

    private static String blankToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Defensive copy helper for API responses. */
    public static List<String> copyOrEmpty(List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new ArrayList<>(labels));
    }
}
