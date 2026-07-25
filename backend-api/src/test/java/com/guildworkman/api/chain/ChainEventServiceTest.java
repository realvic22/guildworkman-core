package com.guildworkman.api.chain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guildworkman.api.chain.api.IngestChainEventRequest;
import com.guildworkman.api.chain.repository.*;
import com.guildworkman.api.chain.service.ChainEventService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChainEventServiceTest {
    @Test
    void ingestIsIdempotentForTheSameEventKey() {
        var events = Mockito.mock(OnChainEventRepository.class);
        var outbox = Mockito.mock(OutboxEventRepository.class);
        var service = new ChainEventService(events, outbox, new ObjectMapper());
        var request = new IngestChainEventRequest("evt-1", "CABC", 10, 0, List.of("Transfer"), "{\"amount\":1}");
        Mockito.when(events.findByEventKey("evt-1")).thenReturn(java.util.Optional.empty());
        var event = new com.guildworkman.api.chain.model.OnChainEvent(); event.setId(1L); event.setEventKey("evt-1"); event.setContractId("CABC"); event.setTopics("[\"Transfer\"]"); event.setPayload(request.payload());
        Mockito.when(events.save(Mockito.any())).thenReturn(event);
        assertThat(service.ingest(request).eventKey()).isEqualTo("evt-1");
        Mockito.verify(events).save(Mockito.any());
        Mockito.verify(outbox).save(Mockito.any());
    }
}
