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
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Mod.EventBusSubscriber(modid = AgentMod.MOD_ID)
public class AgentCommand {

    private static final Set<String> SUBCOMMANDS = Set.of(
        "spawn", "despawn", "status", "stop", "pause", "resume"
    );

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_INPUT = (ctx, builder) -> {
        String remaining = builder.getRemaining();
        if (remaining.startsWith("@") || remaining.isEmpty()) {
            List<String> suggestions = new ArrayList<>();
            // Spawned agents
            for (String name : AgentManager.getInstance().getAgentNames()) {
                suggestions.add("@" + name + " ");
            }
            // Disk agents (for spawn)
            File agentsDir = new File("run/.agent/agents");
            if (agentsDir.isDirectory()) {
                File[] dirs = agentsDir.listFiles(File::isDirectory);
                if (dirs != null) {
                    for (File dir : dirs) {
                        String atName = "@" + dir.getName() + " ";
                        if (!suggestions.contains(atName)) {
                            suggestions.add(atName);
                        }
                    }
                }
            }
            for (String s : suggestions) {
                if (s.toLowerCase().startsWith(remaining.toLowerCase())) {
                    builder.suggest(s);
                }
            }
        } else if (remaining.contains(" ")) {
            // After @name, suggest subcommands
            int spaceIdx = remaining.indexOf(' ');
            String typed = remaining.substring(spaceIdx + 1).trim().toLowerCase();
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(typed)) {
                    builder.suggest(remaining.substring(0, spaceIdx + 1) + sub);
                }
            }
        }
        return builder.buildFuture();
    };

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("agent")
                .then(Commands.literal("list").executes(AgentCommand::list))
                .then(Commands.argument("input", StringArgumentType.greedyString())
                    .suggests(SUGGEST_INPUT)
                    .executes(AgentCommand::dispatch))
        );
    }

    private static int dispatch(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String input = StringArgumentType.getString(ctx, "input");

        // Parse @name prefix
        if (!input.startsWith("@")) {
            source.sendFailure(Component.literal("Usage: /agent @<name> <subcommand|message>"));
            return 0;
        }

        int spaceIdx = input.indexOf(' ');
        if (spaceIdx <= 0) {
            source.sendFailure(Component.literal("Usage: /agent @<name> <subcommand|message>"));
            return 0;
        }

        String name = input.substring(1, spaceIdx);
        String rest = input.substring(spaceIdx + 1).trim();

        if (rest.isEmpty()) {
            source.sendFailure(Component.literal("Usage: /agent @" + name + " <subcommand|message>"));
            return 0;
        }

        return switch (rest.toLowerCase()) {
            case "spawn" -> spawn(source, name);
            case "despawn" -> despawn(source, name);
            case "status" -> status(source, name);
            case "stop" -> stop(source, name);
            case "pause" -> pause(source, name);
            case "resume" -> resume(source, name);
            default -> tell(source, name, rest);
        };
    }

    private static int spawn(CommandSourceStack source, String name) {
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
            source.sendFailure(Component.literal("[" + name + "] Already spawned. Use /agent @" + name + " despawn first."));
        }
        return ok ? 1 : 0;
    }

    private static int despawn(CommandSourceStack source, String name) {
        RuntimeManager.getInstance().stop(name);
        boolean ok = AgentManager.getInstance().despawn(name);
        if (ok) {
            source.sendSuccess(() -> Component.literal("[" + name + "] Despawned"), false);
        } else {
            source.sendFailure(Component.literal("[" + name + "] Not currently spawned."));
        }
        return ok ? 1 : 0;
    }

    private static int tell(CommandSourceStack source, String name, String message) {
        AgentContext agent = AgentManager.getInstance().getAgent(name);
        if (agent == null) {
            source.sendFailure(Component.literal("[" + name + "] Not spawned. Use /agent @" + name + " spawn first."));
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
            ServerPlayer fp = agent.getPlayer();
            String s = agent.isRuntimeRunning() ? "executing" : "idle";
            source.sendSuccess(() -> Component.literal(String.format(
                "  %s [%s] at %.1f %.1f %.1f",
                agent.getName(), s, fp.getX(), fp.getY(), fp.getZ())), false);
        }
        return 1;
    }

    private static int status(CommandSourceStack source, String name) {
        AgentContext agent = AgentManager.getInstance().getAgent(name);
        if (agent == null) {
            source.sendFailure(Component.literal("[" + name + "] Not spawned."));
            return 0;
        }

        ServerPlayer fp = agent.getPlayer();
        String s = agent.isRuntimeRunning() ? "executing" : "idle";
        source.sendSuccess(() -> Component.literal(String.format(
            "[%s] %s at %.1f %.1f %.1f", name, s, fp.getX(), fp.getY(), fp.getZ())), false);
        return 1;
    }

    private static int stop(CommandSourceStack source, String name) {
        RuntimeManager.getInstance().stop(name);
        source.sendSuccess(() -> Component.literal("[" + name + "] Runtime stopped"), false);
        return 1;
    }

    private static int pause(CommandSourceStack source, String name) {
        AgentContext agent = AgentManager.getInstance().getAgent(name);
        if (agent == null) {
            source.sendFailure(Component.literal("[" + name + "] Not spawned."));
            return 0;
        }
        agent.getInterventionQueue().add("[PAUSE] Player requested pause");
        source.sendSuccess(() -> Component.literal("[" + name + "] Pause requested"), false);
        return 1;
    }

    private static int resume(CommandSourceStack source, String name) {
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
