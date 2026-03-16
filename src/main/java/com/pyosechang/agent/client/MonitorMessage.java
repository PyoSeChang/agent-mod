package com.pyosechang.agent.client;

import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * A single formatted event in the monitor conversation log.
 * Lines are pre-formatted Components with terminal font and colors, cached for rendering.
 */
public record MonitorMessage(
    String eventType,
    String agentName,
    long timestamp,
    List<Component> lines
) {}
