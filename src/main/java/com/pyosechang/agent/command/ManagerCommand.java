package com.pyosechang.agent.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.pyosechang.agent.AgentMod;
import com.pyosechang.agent.runtime.ManagerRuntimeManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * /am <message> — Send a message to the Agent Manager runtime.
 * If the manager isn't running, launches it with the message.
 * If it is running, queues the message as an intervention.
 */
@Mod.EventBusSubscriber(modid = AgentMod.MOD_ID)
public class ManagerCommand {

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("am")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                    .executes(ManagerCommand::execute))
        );
    }

    private static int execute(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String message = StringArgumentType.getString(ctx, "message");

        source.sendSuccess(() -> Component.literal("[Manager] > " + message), false);
        ManagerRuntimeManager.getInstance().launchOrMessage(message, source);
        return 1;
    }
}
