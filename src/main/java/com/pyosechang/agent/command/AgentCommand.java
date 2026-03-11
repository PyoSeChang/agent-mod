package com.pyosechang.agent.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.pyosechang.agent.AgentMod;
import com.pyosechang.agent.core.FakePlayerManager;
import com.pyosechang.agent.monitor.InterventionQueue;
import com.pyosechang.agent.runtime.RuntimeManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = AgentMod.MOD_ID)
public class AgentCommand {

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("agent")
                .then(Commands.literal("spawn").executes(AgentCommand::spawn))
                .then(Commands.literal("despawn").executes(AgentCommand::despawn))
                .then(Commands.literal("status").executes(AgentCommand::status))
                .then(Commands.literal("stop").executes(AgentCommand::stop))
                .then(Commands.literal("pause").executes(AgentCommand::pause))
                .then(Commands.literal("resume").executes(AgentCommand::resume))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                    .executes(AgentCommand::sendMessage))
        );
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();

        BlockPos pos;
        if (source.getEntity() != null) {
            pos = source.getEntity().blockPosition().offset(2, 0, 0);
        } else {
            pos = BlockPos.containing(source.getPosition());
        }

        boolean ok = FakePlayerManager.getInstance().spawn(level, pos);
        if (ok) {
            source.sendSuccess(() -> Component.literal(
                "[Agent] Spawned at " + pos.getX() + " " + pos.getY() + " " + pos.getZ()), false);
        } else {
            source.sendFailure(Component.literal("[Agent] Already spawned. Use /agent despawn first."));
        }
        return ok ? 1 : 0;
    }

    private static int despawn(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        boolean ok = FakePlayerManager.getInstance().despawn();
        if (ok) {
            source.sendSuccess(() -> Component.literal("[Agent] Despawned"), false);
        } else {
            source.sendFailure(Component.literal("[Agent] Not currently spawned."));
        }
        return ok ? 1 : 0;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        boolean spawned = FakePlayerManager.getInstance().isSpawned();
        if (spawned) {
            FakePlayer agent = FakePlayerManager.getInstance().getAgent();
            source.sendSuccess(() -> Component.literal(
                "[Agent] Spawned at " +
                String.format("%.1f %.1f %.1f", agent.getX(), agent.getY(), agent.getZ())), false);
        } else {
            source.sendSuccess(() -> Component.literal("[Agent] Not spawned"), false);
        }
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        RuntimeManager.getInstance().stop();
        source.sendSuccess(() -> Component.literal("[Agent] Runtime stopped"), false);
        return 1;
    }

    private static int pause(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        InterventionQueue.getInstance().add("[PAUSE] Player requested pause");
        source.sendSuccess(() -> Component.literal("[Agent] Pause requested"), false);
        return 1;
    }

    private static int resume(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        InterventionQueue.getInstance().add("[RESUME] Player requested resume");
        source.sendSuccess(() -> Component.literal("[Agent] Resume requested"), false);
        return 1;
    }

    private static int sendMessage(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String message = StringArgumentType.getString(ctx, "message");
        source.sendSuccess(() -> Component.literal("[Agent] Launching runtime with message: " + message), false);
        RuntimeManager.getInstance().launch(message, source);
        return 1;
    }
}
