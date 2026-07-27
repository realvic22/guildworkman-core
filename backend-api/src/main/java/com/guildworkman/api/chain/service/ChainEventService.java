package com.guildworkman.api.chain.service;

import com.guildworkman.api.chain.api.*;
import com.guildworkman.api.chain.model.*;
import com.guildworkman.api.chain.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ChainEventService {
    private final OnChainEventRepository events;
    private final OutboxEventRepository outbox;
    private final List<ChainEventHandler> handlers;
    private final ChainEventInserter inserter;

    static final int MAX_ATTEMPTS = 5;

    @Transactional(readOnly = true)
    public ChainEventResponse ingest(IngestChainEventRequest request) {
        return events.findByEventKey(request.eventKey())
                .map(ChainEventResponse::from)
                .orElseGet(() -> insertIdempotently(request));
    }

    private ChainEventResponse insertIdempotently(IngestChainEventRequest request) {
        try {
            return ChainEventResponse.from(inserter.insert(request));
        } catch (DataIntegrityViolationException ex) {
            // Nested REQUIRES_NEW insert rolled back; outer TX can still read the winner.
            return events.findByEventKey(request.eventKey())
                    .map(ChainEventResponse::from)
                    .orElseThrow(() -> new IllegalStateException(
                            "Event not found after idempotent-guard violation for key=" + request.eventKey(), ex));
        }
    }

    @Transactional
    public int replay(ReplayRequest request) {
        int count = 0;
        List<OnChainEvent> batch = events.findByLedgerBetweenOrderByContractIdAscLedgerAscEventIndexAsc(
                request.fromLedger(), request.toLedger());
        Instant now = Instant.now();
        for (OnChainEvent event : batch) {
            event.setStatus(ChainEventStatus.PENDING);
            event.setAttempts(0);
            event.setLastError(null);
            event.setProcessedAt(null);
            event.setNextAttemptAt(now);
            events.save(event);

            outbox.findByEventId(event.getId()).ifPresent(message -> {
                message.setStatus(OutboxStatus.PENDING);
                message.setAttempts(0);
                message.setLastError(null);
                message.setCompletedAt(null);
                message.setNextAttemptAt(now);
                outbox.save(message);
            });
            count++;
        }
        return count;
    }

    @Scheduled(fixedDelayString = "${chain.events.poll-delay-ms:1000}")
    @Transactional
    public void processOne() {
        events.claimNext(
                EnumSet.of(ChainEventStatus.PENDING, ChainEventStatus.PROCESSING),
                Instant.now(),
                PageRequest.of(0, 1)
        ).stream().findFirst().ifPresent(this::process);
    }

    void process(OnChainEvent event) {
        try {
            event.setStatus(ChainEventStatus.PROCESSING);
            event.setAttempts(event.getAttempts() + 1);
            for (ChainEventHandler handler : handlers) {
                handler.handle(event);
            }
            event.setStatus(ChainEventStatus.PROCESSED);
            event.setProcessedAt(Instant.now());
            event.setLastError(null);
            events.save(event);
            outbox.findByEventId(event.getId()).ifPresent(message -> {
                message.setStatus(OutboxStatus.COMPLETED);
                message.setCompletedAt(Instant.now());
                message.setLastError(null);
                outbox.save(message);
            });
        } catch (RuntimeException ex) {
            event.setLastError(ex.getMessage());
            if (event.getAttempts() >= MAX_ATTEMPTS) {
                event.setStatus(ChainEventStatus.DEAD_LETTER);
            } else {
                event.setStatus(ChainEventStatus.PENDING);
                event.setNextAttemptAt(Instant.now().plusSeconds(1L << Math.min(event.getAttempts(), 6)));
            }
            events.save(event);
        }
    }
}
