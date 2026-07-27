package com.guildworkman.api.chain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guildworkman.api.chain.api.IngestChainEventRequest;
import com.guildworkman.api.chain.model.ChainEventStatus;
import com.guildworkman.api.chain.model.OnChainEvent;
import com.guildworkman.api.chain.repository.*;
import com.guildworkman.api.chain.service.ChainEventService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChainEventServiceTest {

    @Test
    void ingestIsIdempotentForTheSameEventKey() {
        var events = Mockito.mock(OnChainEventRepository.class);
        var outbox = Mockito.mock(OutboxEventRepository.class);
        var service = new ChainEventService(events, outbox, new ObjectMapper(), List.of());
        var request = new IngestChainEventRequest("evt-1", "CABC", 10, 0, List.of("Transfer"), "{\"amount\":1}");
        when(events.findByEventKey("evt-1")).thenReturn(Optional.empty());
        var event = new OnChainEvent();
        event.setId(1L);
        event.setEventKey("evt-1");
        event.setContractId("CABC");
        event.setStatus(ChainEventStatus.PENDING);
        event.setTopics("[\"Transfer\"]");
        event.setPayload(request.payload());
        when(events.save(any())).thenReturn(event);
        assertThat(service.ingest(request).eventKey()).isEqualTo("evt-1");
        verify(events).save(any());
        verify(outbox).save(any());
    }

    @Test
    void ingestReturnsExistingEventOnDuplicateEventKey() {
        var events = Mockito.mock(OnChainEventRepository.class);
        var outbox = Mockito.mock(OutboxEventRepository.class);
        var service = new ChainEventService(events, outbox, new ObjectMapper(), List.of());
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
        verify(events, never()).save(any());
        verify(outbox, never()).save(any());
    }

    @Test
    void ingestHandlesDataIntegrityViolationWithFallbackLookup() {
        var events = Mockito.mock(OnChainEventRepository.class);
        var outbox = Mockito.mock(OutboxEventRepository.class);
        var service = new ChainEventService(events, outbox, new ObjectMapper(), List.of());
        var request = new IngestChainEventRequest("race", "CABC", 10, 0, List.of("T"), "{}");

        when(events.findByEventKey("race")).thenReturn(Optional.empty());
        when(events.save(any())).thenThrow(new DataIntegrityViolationException("dup key"));

        var afterSave = new OnChainEvent();
        afterSave.setId(42L);
        afterSave.setEventKey("race");
        afterSave.setContractId("CABC");
        afterSave.setStatus(ChainEventStatus.PENDING);
        afterSave.setTopics("[\"T\"]");
        afterSave.setPayload("{}");

        // After the save fails, createEvent calls findByIdempotentKey again
        when(events.findByEventKey("race")).thenReturn(Optional.empty(), Optional.of(afterSave));

        var resp = service.ingest(request);
        assertThat(resp.id()).isEqualTo(42L);
        assertThat(resp.eventKey()).isEqualTo("race");
    }

    @Test
    void ingestPropagatesUnexpectedExceptionWhenNoEventFound() {
        var events = Mockito.mock(OnChainEventRepository.class);
        var outbox = Mockito.mock(OutboxEventRepository.class);
        var service = new ChainEventService(events, outbox, new ObjectMapper(), List.of());
        var request = new IngestChainEventRequest("boom", "CABC", 10, 0, List.of("T"), "{}");

        when(events.findByEventKey("boom")).thenReturn(Optional.empty());
        when(events.save(any())).thenThrow(new RuntimeException("db connection lost"));

        assertThatThrownBy(() -> service.ingest(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db connection lost");
    }

    @Test
    void replayPersistsChangesExplicitly() {
        var events = Mockito.mock(OnChainEventRepository.class);
        var outbox = Mockito.mock(OutboxEventRepository.class);
        var service = new ChainEventService(events, outbox, new ObjectMapper(), List.of());

        var event = new OnChainEvent();
        event.setId(1L);
        event.setEventKey("r");
        event.setStatus(ChainEventStatus.PROCESSED);
        event.setAttempts(3);
        event.setLastError("err");
        event.setProcessedAt(java.time.Instant.now());
        event.setLedger(10);

        when(events.findByLedgerBetweenOrderByContractIdAscLedgerAscEventIndexAsc(5, 15))
                .thenReturn(List.of(event));

        var replay = new com.guildworkman.api.chain.api.ReplayRequest(5, 15);
        int count = service.replay(replay);

        assertThat(count).isEqualTo(1);
        verify(events).saveAll(any());
    }
}
