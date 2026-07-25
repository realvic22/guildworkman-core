package com.guildworkman.api.chain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "on_chain_events", uniqueConstraints = @UniqueConstraint(name = "uk_chain_event_key", columnNames = "event_key"), indexes = {
        @Index(name = "idx_chain_event_stream_order", columnList = "contract_id,ledger,event_index"),
        @Index(name = "idx_chain_event_status", columnList = "status,next_attempt_at")
})
@Getter
@Setter
@NoArgsConstructor
public class OnChainEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "event_key", nullable = false, updatable = false, length = 128)
    private String eventKey;
    @Column(name = "contract_id", nullable = false, length = 128)
    private String contractId;
    @Column(nullable = false)
    private long ledger;
    @Column(name = "event_index", nullable = false)
    private int eventIndex;
    @Column(nullable = false, length = 256)
    private String topics;
    @Lob @Column(nullable = false)
    private String payload;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private ChainEventStatus status = ChainEventStatus.PENDING;
    @Column(nullable = false)
    private int attempts;
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();
    @Column(length = 1000)
    private String lastError;
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    private Instant processedAt;
    @Version
    private long version;
}
