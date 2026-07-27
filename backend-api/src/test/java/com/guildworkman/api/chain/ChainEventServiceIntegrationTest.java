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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest(properties = {
        "chain.events.poll-delay-ms=3600000",
        "spring.task.scheduling.enabled=false"
})
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
        reset(chainEventHandler);
        outbox.deleteAll();
        events.deleteAll();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
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
        e.setNextAttemptAt(Instant.now().minusSeconds(1));
        return events.saveAndFlush(e);
    }

    private OutboxEvent saveOutbox(Long eventId, OutboxStatus status) {
        OutboxEvent o = new OutboxEvent();
        o.setEventId(eventId);
        o.setStatus(status);
        o.setNextAttemptAt(Instant.now().minusSeconds(1));
        return outbox.saveAndFlush(o);
    }

    @Test
    void ingestCreatesBothEventAndOutboxRow() {
        IngestChainEventRequest req = new IngestChainEventRequest(key("k1"), "C001", 1, 0, List.of("T"), "{}");
        ChainEventResponse resp = service.ingest(req);

        OnChainEvent saved = events.findById(resp.id()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ChainEventStatus.PENDING);

        OutboxEvent msg = outbox.findByEventId(resp.id()).orElseThrow();
        assertThat(msg.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(msg.getEventId()).isEqualTo(saved.getId());
    }

    @Test
    void ingestIsIdempotentForDuplicateEventKey() {
        String eventKey = key("dup");
        IngestChainEventRequest req = new IngestChainEventRequest(eventKey, "C002", 2, 0, List.of("T"), "{}");
        ChainEventResponse first = service.ingest(req);
        ChainEventResponse second = service.ingest(req);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(events.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void processOneTransitionsEventToProcessedAndOutboxToCompleted() {
        OnChainEvent event = saveEvent(key("happy"), ChainEventStatus.PENDING, 0);
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

    @Test
    void failureExhaustingRetriesMovesToDeadLetter() {
        // Seed attempts=4 so one failure increments to 5 (== MAX_ATTEMPTS) → DEAD_LETTER.
        // Do not loop processOne(): backoff after a failure makes nextAttemptAt future,
        // so claimNext will not pick the event up again until that time.
        OnChainEvent event = saveEvent(key("dead"), ChainEventStatus.PENDING, 4);
        saveOutbox(event.getId(), OutboxStatus.PENDING);

        doThrow(new RuntimeException("simulated processing failure"))
                .when(chainEventHandler).handle(any());

        service.processOne();

        OnChainEvent reloaded = events.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChainEventStatus.DEAD_LETTER);
        assertThat(reloaded.getAttempts()).isEqualTo(5);
        assertThat(reloaded.getLastError()).isEqualTo("simulated processing failure");
    }

    @Test
    void failureAppliesExponentialBackoff() {
        OnChainEvent event = saveEvent(key("backoff"), ChainEventStatus.PENDING, 0);
        saveOutbox(event.getId(), OutboxStatus.PENDING);
        Instant before = Instant.now();

        doThrow(new RuntimeException("transient error"))
                .when(chainEventHandler).handle(any());

        service.processOne();

        OnChainEvent reloaded = events.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChainEventStatus.PENDING);
        assertThat(reloaded.getAttempts()).isEqualTo(1);
        // attempts=1 → delay = 1<<1 = 2s
        assertThat(reloaded.getNextAttemptAt()).isAfter(before.plusSeconds(1));
    }

    @Test
    void replayResetsEventAndOutboxStateAndPersists() {
        OnChainEvent event = saveEvent(key("replay"), ChainEventStatus.PROCESSED, 3);
        event.setLastError("some error");
        event.setProcessedAt(Instant.now());
        events.saveAndFlush(event);
        saveOutbox(event.getId(), OutboxStatus.COMPLETED);

        int count = service.replay(new ReplayRequest(100, 100));
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

    @Test
    void replayedEventsCanBeReprocessed() {
        OnChainEvent event = saveEvent(key("reprocess"), ChainEventStatus.PROCESSED, 5);
        event.setProcessedAt(Instant.now());
        events.saveAndFlush(event);
        saveOutbox(event.getId(), OutboxStatus.COMPLETED);

        service.replay(new ReplayRequest(100, 100));
        service.processOne();

        OnChainEvent reloaded = events.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChainEventStatus.PROCESSED);
        assertThat(reloaded.getAttempts()).isEqualTo(1);
        assertThat(reloaded.getProcessedAt()).isNotNull();
    }

    @Test
    void pessimisticLockingPreventsDuplicateProcessing() throws Exception {
        OnChainEvent event = saveEvent(key("concurrent"), ChainEventStatus.PENDING, 0);
        saveOutbox(event.getId(), OutboxStatus.PENDING);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                service.processOne();
                return null;
            });
        }

        List<Future<Void>> futures = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);
        for (Future<Void> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        OnChainEvent reloaded = events.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChainEventStatus.PROCESSED);
        assertThat(reloaded.getAttempts()).isEqualTo(1);
        assertThat(events.countByStatus(ChainEventStatus.PROCESSED)).isEqualTo(1);
    }

    @Test
    void alreadyProcessedEventsAreNotClaimedAgain() {
        OnChainEvent event = saveEvent(key("claimed"), ChainEventStatus.PENDING, 0);
        saveOutbox(event.getId(), OutboxStatus.PENDING);

        service.processOne();
        service.processOne();

        OnChainEvent reloaded = events.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChainEventStatus.PROCESSED);
        assertThat(reloaded.getAttempts()).isEqualTo(1);
    }
}
