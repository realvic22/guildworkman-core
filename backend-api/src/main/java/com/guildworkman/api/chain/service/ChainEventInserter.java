package com.guildworkman.api.chain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guildworkman.api.chain.api.IngestChainEventRequest;
import com.guildworkman.api.chain.model.ChainEventStatus;
import com.guildworkman.api.chain.model.OnChainEvent;
import com.guildworkman.api.chain.model.OutboxEvent;
import com.guildworkman.api.chain.repository.OnChainEventRepository;
import com.guildworkman.api.chain.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Isolated insert so a unique-key race aborts only this nested transaction
 * (Postgres), leaving the caller's transaction able to re-read the winner.
 */
@Service
@RequiredArgsConstructor
public class ChainEventInserter {
    private final OnChainEventRepository events;
    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OnChainEvent insert(IngestChainEventRequest request) {
        OnChainEvent event = new OnChainEvent();
        event.setEventKey(request.eventKey());
        event.setContractId(request.contractId());
        event.setLedger(request.ledger());
        event.setEventIndex(request.eventIndex());
        try {
            event.setTopics(objectMapper.writeValueAsString(request.topics()));
        } catch (Exception ex) {
            throw new IllegalArgumentException("topics must be serializable", ex);
        }
        event.setPayload(request.payload());
        event.setStatus(ChainEventStatus.PENDING);
        event.setNextAttemptAt(Instant.now());
        OnChainEvent saved = events.saveAndFlush(event);
        OutboxEvent message = new OutboxEvent();
        message.setEventId(saved.getId());
        outbox.saveAndFlush(message);
        return saved;
    }
}
