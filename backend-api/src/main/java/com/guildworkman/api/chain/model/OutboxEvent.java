package com.guildworkman.api.chain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "chain_event_outbox", uniqueConstraints = @UniqueConstraint(name = "uk_outbox_event", columnNames = "event_id"), indexes = @Index(name = "idx_outbox_status", columnList = "status,next_attempt_at"))
@Getter @Setter @NoArgsConstructor
public class OutboxEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "event_id", nullable = false, updatable = false)
    private Long eventId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private OutboxStatus status = OutboxStatus.PENDING;
    @Column(nullable = false)
    private int attempts;
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();
    @Column(length = 1000)
    private String lastError;
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    private Instant completedAt;
    @Version
    private long version;
}
