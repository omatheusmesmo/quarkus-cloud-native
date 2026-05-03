package dev.omatheusmesmo.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WebhookRequest(
    @NotBlank @Size(max = 255) String source,
    @NotBlank @Size(max = 100) String eventType,
    String payload
) {}
