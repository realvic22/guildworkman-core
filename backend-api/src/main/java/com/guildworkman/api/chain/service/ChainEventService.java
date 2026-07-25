package com.guildworkman.api.chain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guildworkman.api.chain.api.*;
import com.guildworkman.api.chain.model.*;
import com.guildworkman.api.chain.repository.*;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class ChainEventService {
    private final OnChainEventRepository events;
    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;
    private static final int MAX_ATTEMPTS = 5;

    @Transactional
    public ChainEventResponse ingest(IngestChainEventRequest request) {
        return events.findByEventKey(request.eventKey()).map(ChainEventResponse::from).orElseGet(() -> {
            OnChainEvent event = new OnChainEvent();
            event.setEventKey(request.eventKey()); event.setContractId(request.contractId());
            event.setLedger(request.ledger()); event.setEventIndex(request.eventIndex());
            try { event.setTopics(objectMapper.writeValueAsString(request.topics())); }
            catch (Exception ex) { throw new IllegalArgumentException("topics must be serializable", ex); }
            event.setPayload(request.payload()); event.setStatus(ChainEventStatus.PENDING);
            event.setNextAttemptAt(Instant.now());
            OnChainEvent saved = events.save(event);
            OutboxEvent message = new OutboxEvent(); message.setEventId(saved.getId()); outbox.save(message);
            return ChainEventResponse.from(saved);
        });
    }

    @Transactional
    public int replay(ReplayRequest request) {
        int count = 0;
        for (OnChainEvent event : events.findByLedgerBetweenOrderByContractIdAscLedgerAscEventIndexAsc(request.fromLedger(), request.toLedger())) {
            event.setStatus(ChainEventStatus.PENDING); event.setAttempts(0); event.setLastError(null); event.setProcessedAt(null); event.setNextAttemptAt(Instant.now());
            outbox.findByEventId(event.getId()).ifPresent(message -> { message.setStatus(OutboxStatus.PENDING); message.setAttempts(0); message.setLastError(null); message.setCompletedAt(null); message.setNextAttemptAt(Instant.now()); });
            count++;
        }
        return count;
    }

    @Scheduled(fixedDelayString = "${chain.events.poll-delay-ms:1000}")
    @Transactional
    public void processOne() {
        events.claimNext(EnumSet.of(ChainEventStatus.PENDING, ChainEventStatus.PROCESSING), Instant.now(), PageRequest.of(0, 1)).stream().findFirst().ifPresent(this::process);
    }

    private void process(OnChainEvent event) {
        try {
            event.setStatus(ChainEventStatus.PROCESSING); event.setAttempts(event.getAttempts() + 1);
            // The event row is the durable projection. Keeping the transition in
            // the same transaction as the outbox acknowledgement makes retries safe.
            event.setStatus(ChainEventStatus.PROCESSED); event.setProcessedAt(Instant.now()); event.setLastError(null);
            outbox.findByEventId(event.getId()).ifPresent(message -> { message.setStatus(OutboxStatus.COMPLETED); message.setCompletedAt(Instant.now()); message.setLastError(null); });
        } catch (RuntimeException ex) {
            event.setLastError(ex.getMessage());
            if (event.getAttempts() >= MAX_ATTEMPTS) event.setStatus(ChainEventStatus.DEAD_LETTER); else { event.setStatus(ChainEventStatus.PENDING); event.setNextAttemptAt(Instant.now().plusSeconds(1L << Math.min(event.getAttempts(), 6))); }
        }
    }
}
