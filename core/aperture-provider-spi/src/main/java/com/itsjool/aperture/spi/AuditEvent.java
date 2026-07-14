package com.itsjool.aperture.spi;

import java.time.Instant;

// occurredAt is the moment the audited change actually happened (captured by AuditBridge when
// the Elide lifecycle hook fires, i.e. at transaction-commit time) — not the moment a downstream
// AuditWriter drains its queue and persists/ships the event. The pipeline is asynchronous
// (AuditBridge dispatches on an executor, WebhookAuditWriter batches and flushes on an interval),
// so those two instants can diverge by however long the event sat queued. Represented as Instant
// (Jackson serializes it as an ISO-8601 UTC string by default) because the audit trail is consumed
// cross-language by whatever webhook/SIEM sink is on the other end, and an unambiguous UTC instant
// string needs no timezone-offset interpretation to parse correctly.
public record AuditEvent(String userId, String tenantId, String entity, String entityId, String operation,
                          String detailsJson, Instant occurredAt) {
}
