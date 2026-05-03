package dev.omatheusmesmo.demo.dto;

import dev.omatheusmesmo.demo.entity.Webhook;
import java.time.Instant;

public record WebhookResponse(
    Long id,
    String source,
    String eventType,
    String payload,
    Instant receivedAt,
    String processingMode
) {
    public static WebhookResponse from(Webhook w) {
        return new WebhookResponse(w.id, w.source, w.eventType, w.payload, w.receivedAt, w.processingMode);
    }
}
