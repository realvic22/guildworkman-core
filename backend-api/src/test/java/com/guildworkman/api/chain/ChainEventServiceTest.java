package com.guildworkman.api.chain;

import com.guildworkman.api.chain.api.IngestChainEventRequest;
import com.guildworkman.api.chain.api.ReplayRequest;
import com.guildworkman.api.chain.model.ChainEventStatus;
import com.guildworkman.api.chain.model.OnChainEvent;
import com.guildworkman.api.chain.model.OutboxEvent;
import com.guildworkman.api.chain.model.OutboxStatus;
import com.guildworkman.api.chain.repository.OnChainEventRepository;
import com.guildworkman.api.chain.repository.OutboxEventRepository;
import com.guildworkman.api.chain.service.ChainEventInserter;
import com.guildworkman.api.chain.service.ChainEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChainEventServiceTest {

    private OnChainEventRepository events;
    private OutboxEventRepository outbox;
    private ChainEventInserter inserter;
    private ChainEventService service;

    @BeforeEach
    void setUp() {
        events = mock(OnChainEventRepository.class);
        outbox = mock(OutboxEventRepository.class);
        inserter = mock(ChainEventInserter.class);
        service = new ChainEventService(events, outbox, List.of(), inserter);
    }

    @Test
    void ingestIsIdempotentForTheSameEventKey() {
        var request = new IngestChainEventRequest("evt-1", "CABC", 10, 0, List.of("Transfer"), "{\"amount\":1}");
        when(events.findByEventKey("evt-1")).thenReturn(Optional.empty());

        var event = new OnChainEvent();
        event.setId(1L);
        event.setEventKey("evt-1");
        event.setContractId("CABC");
        event.setStatus(ChainEventStatus.PENDING);
        event.setTopics("[\"Transfer\"]");
        event.setPayload(request.payload());
        when(inserter.insert(request)).thenReturn(event);

        assertThat(service.ingest(request).eventKey()).isEqualTo("evt-1");
        verify(inserter).insert(request);
    }

    @Test
    void ingestReturnsExistingEventOnDuplicateEventKey() {
        var request = new IngestChainEventRequest("evt-dup", "CABC", 10, 0, List.of("Transfer"), "{}");
        var existing = new OnChainEvent();
        existing.setId(1L);
        existing.setEventKey("evt-dup");
        existing.setContractId("CABC");
        existing.setStatus(ChainEventStatus.PENDING);
        existing.setTopics("[\"Transfer\"]");
        existing.setPayload("{}");
        when(events.findByEventKey("evt-dup")).thenReturn(Optional.of(existing));

        var resp = service.ingest(request);
        assertThat(resp.id()).isEqualTo(1L);
        verify(inserter, never()).insert(any());
    }

    @Test
    void ingestHandlesDataIntegrityViolationWithFallbackLookup() {
        var request = new IngestChainEventRequest("race", "CABC", 10, 0, List.of("T"), "{}");
        when(inserter.insert(request)).thenThrow(new DataIntegrityViolationException("dup key"));

        var afterSave = new OnChainEvent();
        afterSave.setId(42L);
        afterSave.setEventKey("race");
        afterSave.setContractId("CABC");
        afterSave.setStatus(ChainEventStatus.PENDING);
        afterSave.setTopics("[\"T\"]");
        afterSave.setPayload("{}");
        when(events.findByEventKey("race")).thenReturn(Optional.empty(), Optional.of(afterSave));

        var resp = service.ingest(request);
        assertThat(resp.id()).isEqualTo(42L);
        assertThat(resp.eventKey()).isEqualTo("race");
    }

    @Test
    void ingestPropagatesUnexpectedExceptionWhenNoEventFound() {
        var request = new IngestChainEventRequest("boom", "CABC", 10, 0, List.of("T"), "{}");
        when(events.findByEventKey("boom")).thenReturn(Optional.empty());
        when(inserter.insert(request)).thenThrow(new RuntimeException("db connection lost"));

        assertThatThrownBy(() -> service.ingest(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db connection lost");
    }

    @Test
    void replayPersistsEventAndOutboxChanges() {
        var event = new OnChainEvent();
        event.setId(1L);
        event.setEventKey("r");
        event.setStatus(ChainEventStatus.PROCESSED);
        event.setAttempts(3);
        event.setLastError("err");
        event.setProcessedAt(java.time.Instant.now());
        event.setLedger(10);

        var message = new OutboxEvent();
        message.setId(9L);
        message.setEventId(1L);
        message.setStatus(OutboxStatus.COMPLETED);

        when(events.findByLedgerBetweenOrderByContractIdAscLedgerAscEventIndexAsc(5, 15))
                .thenReturn(List.of(event));
        when(outbox.findByEventId(1L)).thenReturn(Optional.of(message));

        int count = service.replay(new ReplayRequest(5, 15));

        assertThat(count).isEqualTo(1);
        verify(events).save(event);
        verify(outbox).save(message);
        assertThat(event.getStatus()).isEqualTo(ChainEventStatus.PENDING);
        assertThat(event.getAttempts()).isZero();
        assertThat(message.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }
}
