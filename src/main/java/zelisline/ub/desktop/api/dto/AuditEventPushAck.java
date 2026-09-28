package zelisline.ub.desktop.api.dto;

/**
 * Cloud acknowledgment for {@link AuditEventPushRequest}.
 *
 * @param ingested events newly written to the cloud audit log
 * @param skipped  events already present (id match) or unparseable
 */
public record AuditEventPushAck(int ingested, int skipped) {}
