package com.guildworkman.api.chain.api;

import com.guildworkman.api.chain.service.ChainEventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController @RequestMapping("/api/v1/chain/events") @RequiredArgsConstructor
public class ChainEventController {
    private final ChainEventService service;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ChainEventResponse ingest(@Valid @RequestBody IngestChainEventRequest request) { return service.ingest(request); }

    @PostMapping("/replay")
    public Map<String, Integer> replay(@Valid @RequestBody ReplayRequest request) { return Map.of("replayed", service.replay(request)); }
}
