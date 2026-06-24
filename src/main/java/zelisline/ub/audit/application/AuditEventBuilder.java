package zelisline.ub.audit.application;

import java.time.Instant;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventPayload;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.platform.web.CorrelationIdFilter;

/**
 * Convenience builder for {@link AuditEventPayload}.
 *
 * <p>Pulls the correlation id from SLF4J MDC when available, so callers do not need
 * to pass it explicitly in the request path.
 */
@Component
public class AuditEventBuilder {

    private final ObjectMapper objectMapper;

    public AuditEventBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Builder builder(AuditEventCategory category, String eventType, AuditEventSeverity severity) {
        return new Builder(objectMapper, category, eventType, severity);
    }

    public static final class Builder {

        private final ObjectMapper objectMapper;
        private final AuditEventCategory category;
        private final String eventType;
        private final AuditEventSeverity severity;

        private String businessId;
        private String branchId;
        private String actorId;
        private AuditEventActorType actorType = AuditEventActorType.USER;
        private String actorName;
        private String targetType;
        private String targetId;
        private String targetLabel;
        private String sessionId;
        private String correlationId;
        private String ipAddress;
        private String userAgent;
        private String source;
        private String terminalId;
        private String shiftId;
        private Object oldState;
        private Object newState;
        private Object diff;
        private String reason;
        private Object metadata;
        private Instant createdAt;

        private Builder(ObjectMapper objectMapper, AuditEventCategory category, String eventType, AuditEventSeverity severity) {
            this.objectMapper = objectMapper;
            this.category = category;
            this.eventType = eventType;
            this.severity = severity;
            this.correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
        }

        public Builder businessId(String businessId) {
            this.businessId = businessId;
            return this;
        }

        public Builder branchId(String branchId) {
            this.branchId = branchId;
            return this;
        }

        public Builder actor(String actorId, AuditEventActorType actorType) {
            this.actorId = actorId;
            this.actorType = actorType;
            return this;
        }

        public Builder actorName(String actorName) {
            this.actorName = actorName;
            return this;
        }

        public Builder target(String targetType, String targetId) {
            this.targetType = targetType;
            this.targetId = targetId;
            return this;
        }

        public Builder targetLabel(String targetLabel) {
            this.targetLabel = targetLabel;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder ipAddress(String ipAddress) {
            this.ipAddress = ipAddress;
            return this;
        }

        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public Builder terminalId(String terminalId) {
            this.terminalId = terminalId;
            return this;
        }

        public Builder shiftId(String shiftId) {
            this.shiftId = shiftId;
            return this;
        }

        public Builder oldState(Object oldState) {
            this.oldState = oldState;
            return this;
        }

        public Builder newState(Object newState) {
            this.newState = newState;
            return this;
        }

        public Builder diff(Object diff) {
            this.diff = diff;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder metadata(Object metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public AuditEventPayload build() {
            return AuditEventPayload.builder()
                    .businessId(businessId)
                    .branchId(branchId)
                    .category(category)
                    .eventType(eventType)
                    .severity(severity)
                    .actorId(actorId)
                    .actorType(actorType)
                    .actorName(actorName)
                    .targetType(targetType)
                    .targetId(targetId)
                    .targetLabel(targetLabel)
                    .sessionId(sessionId)
                    .correlationId(correlationId)
                    .ipAddress(ipAddress)
                    .userAgent(userAgent)
                    .source(source)
                    .terminalId(terminalId)
                    .shiftId(shiftId)
                    .oldState(toJson(oldState))
                    .newState(toJson(newState))
                    .diff(toJson(diff))
                    .reason(reason)
                    .metadata(toJson(metadata))
                    .createdAt(createdAt)
                    .build();
        }

        private String toJson(Object value) {
            if (value == null) {
                return null;
            }
            if (value instanceof String s) {
                return s;
            }
            try {
                return objectMapper.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Failed to serialize audit event field", e);
            }
        }
    }
}
