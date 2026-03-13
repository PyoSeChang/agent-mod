package com.pyosechang.agent.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.pyosechang.agent.AgentMod;
import com.pyosechang.agent.core.AgentContext;
import com.pyosechang.agent.core.AgentManager;
import com.pyosechang.agent.runtime.RuntimeManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = AgentMod.MOD_ID)
public class AgentCommand {

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_SPAWNED = (ctx, builder) ->
        SharedSuggestionProvider.suggest(AgentManager.getInstance().getAgentNames(), builder);

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("agent")
                .then(Commands.literal("spawn")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(AgentCommand::spawn)))
                .then(Commands.literal("despawn")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(SUGGEST_SPAWNED)
                        .executes(AgentCommand::despawn)))
                .then(Commands.literal("tell")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(SUGGEST_SPAWNED)
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                            .executes(AgentCommand::tell))))
                .then(Commands.literal("list").executes(AgentCommand::list))
                .then(Commands.literal("status")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(SUGGEST_SPAWNED)
                        .executes(AgentCommand::status)))
                .then(Commands.literal("stop")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(SUGGEST_SPAWNED)
                        .executes(AgentCommand::stop)))
                .then(Commands.literal("pause")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(SUGGEST_SPAWNED)
                        .executes(AgentCommand::pause)))
                .then(Commands.literal("resume")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(SUGGEST_SPAWNED)
                        .executes(AgentCommand::resume)))
        );
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        ServerLevel level = source.getLevel();

        BlockPos pos;
        if (source.getEntity() != null) {
            pos = source.getEntity().blockPosition().offset(2, 0, 0);
        } else {
            pos = BlockPos.containing(source.getPosition());
        }

        boolean ok = AgentManager.getInstance().spawn(name, level, pos);
        if (ok) {
            source.sendSuccess(() -> Component.literal(
                "[" + name + "] Spawned at " + pos.getX() + " " + pos.getY() + " " + pos.getZ()), false);
        } else {
            source.sendFailure(Component.literal("[" + name + "] Already spawned. Use /agent despawn " + name + " first."));
        }
        return ok ? 1 : 0;
    }

    private static int despawn(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");

        RuntimeManager.getInstance().stop(name);
        boolean ok = AgentManager.getInstance().despawn(name);
        if (ok) {
            source.sendSuccess(() -> Component.literal("[" + name + "] Despawned"), false);
        } else {
            source.sendFailure(Component.literal("[" + name + "] Not currently spawned."));
        }
        return ok ? 1 : 0;
    }

    private static int tell(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        String message = StringArgumentType.getString(ctx, "message");

        AgentContext agent = AgentManager.getInstance().getAgent(name);
        if (agent == null) {
            source.sendFailure(Component.literal("[" + name + "] Not spawned. Use /agent spawn " + name + " first."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("[" + name + "] > " + message), false);
        RuntimeManager.getInstance().launch(name, message, source);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        AgentManager manager = AgentManager.getInstance();

        if (manager.getAgentCount() == 0) {
            source.sendSuccess(() -> Component.literal("[Agent] No agents spawned."), false);
            return 1;
        }

        source.sendSuccess(() -> Component.literal("[Agent] Spawned agents (" + manager.getAgentCount() + "):"), false);
        for (AgentContext agent : manager.getAllAgents()) {
            FakePlayer fp = agent.getFakePlayer();
            String status = agent.isRuntimeRunning() ? "executing" : "idle";
            source.sendSuccess(() -> Component.literal(String.format(
                "  %s [%s] at %.1f %.1f %.1f",
                agent.getName(), status, fp.getX(), fp.getY(), fp.getZ())), false);
        }
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");

        AgentContext agent = AgentManager.getInstance().getAgent(name);
        if (agent == null) {
            source.sendFailure(Component.literal("[" + name + "] Not spawned."));
            return 0;
        }

        FakePlayer fp = agent.getFakePlayer();
        String status = agent.isRuntimeRunning() ? "executing" : "idle";
        source.sendSuccess(() -> Component.literal(String.format(
            "[%s] %s at %.1f %.1f %.1f", name, status, fp.getX(), fp.getY(), fp.getZ())), false);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");

        RuntimeManager.getInstance().stop(name);
        source.sendSuccess(() -> Component.literal("[" + name + "] Runtime stopped"), false);
        return 1;
    }

    private static int pause(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");

        AgentContext agent = AgentManager.getInstance().getAgent(name);
        if (agent == null) {
            source.sendFailure(Component.literal("[" + name + "] Not spawned."));
            return 0;
        }
        agent.getInterventionQueue().add("[PAUSE] Player requested pause");
        source.sendSuccess(() -> Component.literal("[" + name + "] Pause requested"), false);
        return 1;
    }

    private static int resume(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");

        AgentContext agent = AgentManager.getInstance().getAgent(name);
        if (agent == null) {
            source.sendFailure(Component.literal("[" + name + "] Not spawned."));
            return 0;
        }
        agent.getInterventionQueue().add("[RESUME] Player requested resume");
        source.sendSuccess(() -> Component.literal("[" + name + "] Resume requested"), false);
        return 1;
    }
}
