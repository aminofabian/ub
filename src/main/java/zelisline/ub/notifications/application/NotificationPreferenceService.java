package zelisline.ub.notifications.application;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.notifications.NotificationCategories;
import zelisline.ub.notifications.domain.NotificationDelivery;
import zelisline.ub.notifications.domain.NotificationPreference;
import zelisline.ub.notifications.domain.NotificationQuietHours;
import zelisline.ub.notifications.repository.NotificationPreferenceRepository;
import zelisline.ub.notifications.repository.NotificationQuietHoursRepository;
import zelisline.ub.notifications.repository.NotificationRepository;

@Service
@RequiredArgsConstructor
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository preferenceRepository;
    private final NotificationQuietHoursRepository quietHoursRepository;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void ensureMigrated(String businessId, String userId) {
        migrateFromLegacySettingsIfNeeded(businessId, userId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getProfile(String businessId, String userId) {
        ensureMigrated(businessId, userId);
        Map<String, Object> profile = new LinkedHashMap<>();
        NotificationQuietHours qh = quietHoursRepository.findByUserIdAndBusinessId(userId, businessId)
                .orElse(defaultQuietHours(userId, businessId));
        profile.put("quietHoursEnabled", qh.isEnabled());
        profile.put("quietHoursStart", qh.getStartLocal().toString().substring(0, 5));
        profile.put("quietHoursEnd", qh.getEndLocal().toString().substring(0, 5));
        profile.put("timezone", qh.getTimezone());
        profile.put("allowHighPriorityDuringQuietHours", qh.isAllowHighPriority());
        profile.put("promotionalEnabled", qh.isPromotionalEnabled());
        profile.put("maxPromotionalPerDay", qh.getMaxPromotionalPerDay());
        profile.put("categories", buildCategoryMap(businessId, userId));
        profile.put("mutedTypes", List.of());
        return profile;
    }

    @Transactional
    public Map<String, Object> updateProfile(String businessId, String userId, Map<String, Object> body) {
        migrateFromLegacySettingsIfNeeded(businessId, userId);
        NotificationQuietHours qh = quietHoursRepository.findByUserIdAndBusinessId(userId, businessId)
                .orElseGet(() -> defaultQuietHours(userId, businessId));
        qh.setEnabled(Boolean.TRUE.equals(body.get("quietHoursEnabled")));
        qh.setTimezone(stringOr(body.get("timezone"), "Africa/Nairobi"));
        qh.setStartLocal(parseTime(stringOr(body.get("quietHoursStart"), "22:00")));
        qh.setEndLocal(parseTime(stringOr(body.get("quietHoursEnd"), "07:00")));
        if (body.get("allowHighPriorityDuringQuietHours") != null) {
            qh.setAllowHighPriority(Boolean.TRUE.equals(body.get("allowHighPriorityDuringQuietHours")));
        }
        if (body.get("promotionalEnabled") != null) {
            qh.setPromotionalEnabled(Boolean.TRUE.equals(body.get("promotionalEnabled")));
        }
        if (body.get("maxPromotionalPerDay") instanceof Number n) {
            qh.setMaxPromotionalPerDay(Math.max(0, n.intValue()));
        }
        quietHoursRepository.save(qh);

        @SuppressWarnings("unchecked")
        Map<String, Object> categories = body.get("categories") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        for (var entry : categories.entrySet()) {
            String category = entry.getKey();
            if (!(entry.getValue() instanceof Map<?, ?> channelMap)) {
                continue;
            }
            for (var ch : channelMap.entrySet()) {
                boolean enabled = Boolean.TRUE.equals(ch.getValue());
                upsertPreference(businessId, userId, category, String.valueOf(ch.getKey()), enabled);
            }
        }
        return getProfile(businessId, userId);
    }

    @Transactional(readOnly = true)
    public boolean isChannelEnabled(String businessId, String userId, String category, String channel) {
        ensureMigrated(businessId, userId);
        return preferenceRepository
                .findByBusinessIdAndUserIdAndCategoryAndChannel(businessId, userId, category, channel)
                .map(NotificationPreference::isEnabled)
                .orElse(true);
    }

    @Transactional(readOnly = true)
    public boolean isInQuietHours(String businessId, String userId, boolean highPriority) {
        ensureMigrated(businessId, userId);
        NotificationQuietHours qh = quietHoursRepository.findByUserIdAndBusinessId(userId, businessId).orElse(null);
        if (qh == null || !qh.isEnabled()) {
            return false;
        }
        if (highPriority && qh.isAllowHighPriority()) {
            return false;
        }
        ZoneId zone = ZoneId.of(qh.getTimezone());
        LocalTime now = ZonedDateTime.now(zone).toLocalTime();
        LocalTime start = qh.getStartLocal();
        LocalTime end = qh.getEndLocal();
        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        return !now.isBefore(start) || now.isBefore(end);
    }

    @Transactional(readOnly = true)
    public boolean isPromotionalDeliveryAllowed(String businessId, String userId) {
        ensureMigrated(businessId, userId);
        NotificationQuietHours qh = quietHoursRepository.findByUserIdAndBusinessId(userId, businessId)
                .orElse(defaultQuietHours(userId, businessId));
        if (!qh.isPromotionalEnabled()) {
            return false;
        }
        Instant since = Instant.now().minusSeconds(86_400);
        long count = notificationRepository.countPromotionalSince(businessId, userId, since);
        return count < qh.getMaxPromotionalPerDay();
    }

    private void migrateFromLegacySettingsIfNeeded(String businessId, String userId) {
        if (quietHoursRepository.findByUserIdAndBusinessId(userId, businessId).isPresent()) {
            return;
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getSettings() == null || user.getSettings().isBlank()) {
            quietHoursRepository.save(defaultQuietHours(userId, businessId));
            seedDefaultPreferences(businessId, userId);
            return;
        }
        try {
            Map<String, Object> legacy = objectMapper.readValue(user.getSettings(), new TypeReference<>() {});
            NotificationQuietHours qh = defaultQuietHours(userId, businessId);
            qh.setEnabled(Boolean.TRUE.equals(legacy.get("quietHoursEnabled")));
            qh.setStartLocal(parseTime(stringOr(legacy.get("quietHoursStart"), "22:00")));
            qh.setEndLocal(parseTime(stringOr(legacy.get("quietHoursEnd"), "07:00")));
            quietHoursRepository.save(qh);
            seedDefaultPreferences(businessId, userId);
        } catch (Exception e) {
            quietHoursRepository.save(defaultQuietHours(userId, businessId));
            seedDefaultPreferences(businessId, userId);
        }
    }

    private void seedDefaultPreferences(String businessId, String userId) {
        for (String category : List.of(
                NotificationCategories.ORDERS,
                NotificationCategories.PROMO,
                NotificationCategories.ENGAGEMENT,
                NotificationCategories.CREDITS,
                NotificationCategories.INVENTORY,
                NotificationCategories.SALES,
                NotificationCategories.INSIGHTS)) {
            upsertPreference(businessId, userId, category, NotificationDelivery.CHANNEL_IN_APP, true);
            upsertPreference(businessId, userId, category, NotificationDelivery.CHANNEL_WEB_PUSH, true);
            upsertPreference(businessId, userId, category, NotificationDelivery.CHANNEL_EMAIL, true);
        }
    }

    private void upsertPreference(String businessId, String userId, String category, String channel, boolean enabled) {
        NotificationPreference pref = preferenceRepository
                .findByBusinessIdAndUserIdAndCategoryAndChannel(businessId, userId, category, channel)
                .orElseGet(() -> {
                    NotificationPreference p = new NotificationPreference();
                    p.setBusinessId(businessId);
                    p.setUserId(userId);
                    p.setCategory(category);
                    p.setChannel(channel);
                    return p;
                });
        pref.setEnabled(enabled);
        preferenceRepository.save(pref);
    }

    private Map<String, Object> buildCategoryMap(String businessId, String userId) {
        Map<String, Object> categories = new LinkedHashMap<>();
        for (NotificationPreference p : preferenceRepository.findByBusinessIdAndUserId(businessId, userId)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> channels = (Map<String, Object>) categories.computeIfAbsent(
                    p.getCategory(), k -> new LinkedHashMap<String, Object>());
            channels.put(p.getChannel(), p.isEnabled());
        }
        return categories;
    }

    private static NotificationQuietHours defaultQuietHours(String userId, String businessId) {
        NotificationQuietHours qh = new NotificationQuietHours();
        qh.setUserId(userId);
        qh.setBusinessId(businessId);
        qh.setEnabled(false);
        qh.setTimezone("Africa/Nairobi");
        qh.setStartLocal(LocalTime.of(22, 0));
        qh.setEndLocal(LocalTime.of(7, 0));
        qh.setAllowHighPriority(true);
        qh.setPromotionalEnabled(true);
        qh.setMaxPromotionalPerDay(3);
        return qh;
    }

    private static LocalTime parseTime(String raw) {
        String t = raw.trim();
        if (t.length() == 5) {
            return LocalTime.parse(t + ":00");
        }
        return LocalTime.parse(t);
    }

    private static String stringOr(Object raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String s = String.valueOf(raw).trim();
        return s.isEmpty() ? fallback : s;
    }
}
