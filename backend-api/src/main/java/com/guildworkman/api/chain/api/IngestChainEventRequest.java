package com.guildworkman.api.chain.api;

import jakarta.validation.constraints.*;
import java.util.List;

public record IngestChainEventRequest(
        @NotBlank @Size(max = 128) String eventKey,
        @NotBlank @Size(max = 128) String contractId,
        @PositiveOrZero long ledger,
        @PositiveOrZero int eventIndex,
        @NotEmpty List<@NotBlank @Size(max = 128) String> topics,
        @NotBlank String payload) {}
