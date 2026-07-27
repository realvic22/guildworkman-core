package com.guildworkman.api.chain;

import com.guildworkman.api.chain.api.ChainEventResponse;
import com.guildworkman.api.chain.api.IngestChainEventRequest;
import com.guildworkman.api.chain.api.ReplayRequest;
import com.guildworkman.api.chain.model.ChainEventStatus;
import com.guildworkman.api.chain.model.OnChainEvent;
import com.guildworkman.api.chain.model.OutboxEvent;
import com.guildworkman.api.chain.model.OutboxStatus;
import com.guildworkman.api.chain.repository.OnChainEventRepository;
import com.guildworkman.api.chain.repository.OutboxEventRepository;
import com.guildworkman.api.chain.service.ChainEventHandler;
import com.guildworkman.api.chain.service.ChainEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(properties = { "chain.events.poll-delay-ms=60000" })
class ChainEventServiceIntegrationTest {

    @Autowired
    private OnChainEventRepository events;

    @Autowired
    private OutboxEventRepository outbox;

    @Autowired
    private ChainEventService service;

    @MockBean
    private ChainEventHandler chainEventHandler;

    @BeforeEach
    void cleanSlate() {
        outbox.deleteAll();
        events.deleteAll();
    }

    private OnChainEvent saveEvent(String eventKey, ChainEventStatus status, int attempts) {
        OnChainEvent e = new OnChainEvent();
        e.setEventKey(eventKey);
        e.setContractId("CTEST");
        e.setLedger(100);
        e.setEventIndex(0);
        e.setTopics("[\"Test\"]");
        e.setPayload("{\"x\":1}");
        e.setStatus(status);
        e.setAttempts(attempts);
        e.setNextAttemptAt(java.time.Instant.now());
        return events.save(e);
    }

    private OutboxEvent saveOutbox(Long eventId, OutboxStatus status) {
        OutboxEvent o = new OutboxEvent();
        o.setEventId(eventId);
        o.setStatus(status);
        return outbox.save(o);
    }

    // -------------------------------------------------------------------------
    // 1. Transactional outbox: ingest creates both rows atomically
    // -------------------------------------------------------------------------

    @Test
    void ingestCreatesBothEventAndOutboxRow() {
        IngestChainEventRequest req = new IngestChainEventRequest("k1", "C001", 1, 0, List.of("T"), "{}");
        ChainEventResponse resp = service.ingest(req);

        OnChainEvent saved = events.findById(resp.id()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ChainEventStatus.PENDING);

        OutboxEvent msg = outbox.findByEventId(resp.id()).orElseThrow();
        assertThat(msg.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(msg.getEventId()).isEqualTo(saved.getId());
    }

    // -------------------------------------------------------------------------
    // 2. Ingest idempotency
    // -------------------------------------------------------------------------

    @Test
    void ingestIsIdempotentForDuplicateEventKey() {
        IngestChainEventRequest req = new IngestChainEventRequest("dup", "C002", 2, 0, List.of("T"), "{}");
        ChainEventResponse first = service.ingest(req);
        ChainEventResponse second = service.ingest(req);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(events.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // 3. Happy-path processing
    // -------------------------------------------------------------------------

    @Test
    void processOneTransitionsEventToProcessedAndOutboxToCompleted() {
        OnChainEvent event = saveEvent("happy", ChainEventStatus.PENDING, 0);
        saveOutbox(event.getId(), OutboxStatus.PENDING);

        service.processOne();

        OnChainEvent reloaded = events.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChainEventStatus.PROCESSED);
        assertThat(reloaded.getAttempts()).isEqualTo(1);
        assertThat(reloaded.getProcessedAt()).isNotNull();

        OutboxEvent msg = outbox.findByEventId(event.getId()).orElseThrow();
        assertThat(msg.getStatus()).isEqualTo(OutboxStatus.COMPLETED);
        assertThat(msg.getCompletedAt()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // 4. Failure path: handler exception -> DEAD_LETTER after MAX_ATTEMPTS
    // -------------------------------------------------------------------------

    @Test
    void failureExhaustingRetriesMovesToDeadLetter() {
        OnChainEvent event = saveEvent("dead", ChainEventStatus.PENDING, 0);
        saveOutbox(event.getId(), OutboxStatus.PENDING);

        doThrow(new RuntimeException("simulated processing failure"))
                .when(chainEventHandler).handle(any());

        for (int i = 0; i < 5; i++) {
            try {
                service.processOne();
            } catch (RuntimeException ignored) {
            }
        }

        OnChainEvent reloaded = events.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChainEventStatus.DEAD_LETTER);
        assertThat(reloaded.getAttempts()).isEqualTo(5);
        assertThat(reloaded.getLastError()).isEqualTo("simulated processing failure");
    }

    // -------------------------------------------------------------------------
    // 5. Backoff after failure
    // -------------------------------------------------------------------------

    @Test
    void failureAppliesExponentialBackoff() {
        OnChainEvent event = saveEvent("backoff", ChainEventStatus.PENDING, 0);
        saveOutbox(event.getId(), OutboxStatus.PENDING);

        doThrow(new RuntimeException("transient error"))
                .when(chainEventHandler).handle(any());

        try {
            service.processOne();
        } catch (RuntimeException ignored) {
        }

        OnChainEvent reloaded = events.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChainEventStatus.PENDING);
        assertThat(reloaded.getAttempts()).isEqualTo(1);
        assertThat(reloaded.getNextAttemptAt()).isAfter(java.time.Instant.now());
    }

    // -------------------------------------------------------------------------
    // 6. Replay persists changes
    // -------------------------------------------------------------------------

    @Test
    void replayResetsEventAndOutboxStateAndPersists() {
        OnChainEvent event = saveEvent("replay-me", ChainEventStatus.PROCESSED, 3);
        event.setLastError("some error");
        events.save(event);
        saveOutbox(event.getId(), OutboxStatus.COMPLETED);

        ReplayRequest req = new ReplayRequest(100, 100);
        int count = service.replay(req);

        assertThat(count).isEqualTo(1);

        OnChainEvent reloaded = events.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChainEventStatus.PENDING);
        assertThat(reloaded.getAttempts()).isZero();
        assertThat(reloaded.getLastError()).isNull();
        assertThat(reloaded.getProcessedAt()).isNull();

        OutboxEvent msg = outbox.findByEventId(event.getId()).orElseThrow();
        assertThat(msg.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(msg.getAttempts()).isZero();
        assertThat(msg.getLastError()).isNull();
        assertThat(msg.getCompletedAt()).isNull();
    }

    // -------------------------------------------------------------------------
    // 7. Replayed events can be reprocessed
    // -------------------------------------------------------------------------

    @Test
    void replayedEventsCanBeReprocessed() {
        OnChainEvent event = saveEvent("reprocess-me", ChainEventStatus.PROCESSED, 5);
        saveOutbox(event.getId(), OutboxStatus.COMPLETED);

        service.replay(new ReplayRequest(100, 100));
        service.processOne();

        OnChainEvent reloaded = events.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChainEventStatus.PROCESSED);
        assertThat(reloaded.getAttempts()).isEqualTo(1);
        assertThat(reloaded.getProcessedAt()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // 8. Concurrency: pessimistic locking prevents duplicate processing
    // -------------------------------------------------------------------------

    @Test
    void pessimisticLockingPreventsDuplicateProcessing() throws Exception {
        OnChainEvent event = saveEvent("concurrent", ChainEventStatus.PENDING, 0);
        saveOutbox(event.getId(), OutboxStatus.PENDING);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        List<Callable<Void>> tasks = java.util.stream.IntStream.range(0, threads)
                .mapToObj(i -> (Callable<Void>) () -> { service.processOne(); return null; })
                .toList();

        for (Future<Void> f : pool.invokeAll(tasks)) {
            f.get();
        }
        pool.shutdown();

        OnChainEvent reloaded = events.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChainEventStatus.PROCESSED);
        assertThat(reloaded.getAttempts()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // 9. Processed events are not claimed again
    // -------------------------------------------------------------------------

    @Test
    void alreadyProcessedEventsAreNotClaimedAgain() {
        OnChainEvent event = saveEvent("claimed", ChainEventStatus.PENDING, 0);
        saveOutbox(event.getId(), OutboxStatus.PENDING);

        service.processOne();

        OnChainEvent reloaded = events.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChainEventStatus.PROCESSED);
        assertThat(reloaded.getAttempts()).isEqualTo(1);

        service.processOne();

        reloaded = events.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getAttempts()).isEqualTo(1);
    }
}
