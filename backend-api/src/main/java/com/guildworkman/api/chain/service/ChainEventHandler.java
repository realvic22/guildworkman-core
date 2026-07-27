package com.guildworkman.api.chain.service;

import com.guildworkman.api.chain.model.OnChainEvent;

@FunctionalInterface
public interface ChainEventHandler {
    void handle(OnChainEvent event);
}
