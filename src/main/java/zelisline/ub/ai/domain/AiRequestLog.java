package zelisline.ub.ai.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "ai_request_log")
@Getter
@Setter
public class AiRequestLog {

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(name = "business_id", length = 36, nullable = false)
    private String businessId;

    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(name = "skill", length = 64, nullable = false)
    private String skill;

    @Column(name = "surface", length = 128)
    private String surface;

    @Column(name = "route_path", length = 512)
    private String routePath;

    @Column(name = "provider", length = 32)
    private String provider;

    @Column(name = "model", length = 128)
    private String model;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "success", nullable = false)
    private boolean success = true;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "feedback", length = 16)
    private String feedback;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
