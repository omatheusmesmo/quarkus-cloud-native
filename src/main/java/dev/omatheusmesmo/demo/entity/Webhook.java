package dev.omatheusmesmo.demo.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import java.time.Instant;

@Entity
public class Webhook extends PanacheEntity {

    @Column(nullable = false)
    public String source;

    @Column(nullable = false)
    public String eventType;

    @Column(columnDefinition = "TEXT")
    public String payload;

    public Instant receivedAt;

    public String processingMode;
}
