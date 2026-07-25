package com.guildworkman.api.chain.api;

import jakarta.validation.constraints.PositiveOrZero;

public record ReplayRequest(@PositiveOrZero long fromLedger, @PositiveOrZero long toLedger) {
    public ReplayRequest { if (toLedger < fromLedger) throw new IllegalArgumentException("toLedger must be greater than or equal to fromLedger"); }
}
