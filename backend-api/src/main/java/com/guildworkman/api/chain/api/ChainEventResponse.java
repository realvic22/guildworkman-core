package com.guildworkman.api.chain.api;

import com.guildworkman.api.chain.model.OnChainEvent;
import java.time.Instant;

public record ChainEventResponse(Long id, String eventKey, String contractId, long ledger, int eventIndex, String topics, String payload, String status, int attempts, Instant processedAt) {
    public static ChainEventResponse from(OnChainEvent e) { return new ChainEventResponse(e.getId(), e.getEventKey(), e.getContractId(), e.getLedger(), e.getEventIndex(), e.getTopics(), e.getPayload(), e.getStatus().name(), e.getAttempts(), e.getProcessedAt()); }
}
