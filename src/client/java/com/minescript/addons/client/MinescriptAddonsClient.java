package com.minescript.addons.client;

import com.minescript.addons.command.ModCommands;
import net.fabricmc.api.ClientModInitializer;

public class MinescriptAddonsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ModCommands.register();
    }
}
