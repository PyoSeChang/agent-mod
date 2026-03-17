package com.pyosechang.agent.core;

import com.mojang.logging.LogUtils;
import com.pyosechang.agent.AgentMod;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Handles agent bed lifecycle:
 * - Give bed item (with AgentBed NBT) to player (no duplicates)
 * - Detect placement → save position to agent config
 * - Break old bed when re-placing or re-giving
 * - Move dormant agent to new bed position
 */
@Mod.EventBusSubscriber(modid = AgentMod.MOD_ID)
public class AgentBedHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String NBT_KEY = "AgentBed";

    /**
     * Give a player an agent bed item.
     * - Removes any existing agent bed items from player inventory
     * - Breaks existing placed bed block
     * - Removes dormant agent entity at old bed
     */
    public static void giveBedItem(ServerPlayer player, String agentName) {
        // 1. Remove existing agent bed items from inventory
        removeAgentBedItems(player, agentName);

        // 2. Break existing placed bed if any
        AgentConfig config = AgentConfig.load(agentName);
        if (config.hasBed()) {
            breakPlacedBed(player, config, agentName);
        }

        // 3. Give new bed item
        ItemStack bed = new ItemStack(Items.RED_BED, 1);
        CompoundTag tag = bed.getOrCreateTag();
        tag.putString(NBT_KEY, agentName);
        bed.setHoverName(Component.literal("[" + agentName + "] Bed")
            .withStyle(s -> s.withColor(0x55AAFF).withItalic(false)));

        if (!player.getInventory().add(bed)) {
            player.drop(bed, false);
        }
        LOGGER.info("Gave agent bed for '{}' to player '{}'", agentName, player.getName().getString());
    }

    /**
     * Remove all agent bed items for the given agent from player inventory.
     */
    private static void removeAgentBedItems(ServerPlayer player, String agentName) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (isAgentBed(stack) && agentName.equals(stack.getTag().getString(NBT_KEY))) {
                player.getInventory().setItem(i, ItemStack.EMPTY);
            }
        }
    }

    /**
     * Break the existing bed block and clean up dormant agent.
     */
    private static void breakPlacedBed(ServerPlayer player, AgentConfig config, String agentName) {
        if (!(player.level() instanceof ServerLevel level)) return;
        BlockPos bedPos = new BlockPos(config.getBedX(), config.getBedY(), config.getBedZ());

        // Remove dormant agent entity at old bed
        AgentManager mgr = AgentManager.getInstance();
        if (mgr.isDormant(agentName)) {
            mgr.removeDormant(agentName);
        }

        // Remove bed blocks (both FOOT and HEAD) without item drops
        BlockState state = level.getBlockState(bedPos);
        if (state.getBlock() instanceof BedBlock) {
            // Find and remove the other half first
            if (state.hasProperty(BedBlock.PART) && state.hasProperty(BedBlock.FACING)) {
                net.minecraft.core.Direction facing = state.getValue(BedBlock.FACING);
                BlockPos otherPos = state.getValue(BedBlock.PART) == BedPart.FOOT
                    ? bedPos.relative(facing)      // FOOT → HEAD is in facing direction
                    : bedPos.relative(facing.getOpposite()); // HEAD → FOOT is opposite
                level.removeBlock(otherPos, false);
            }
            level.removeBlock(bedPos, false);
        }

        // Clear config
        config.clearBed();
        config.save(agentName);

        AgentContext ctx = mgr.getAgent(agentName);
        if (ctx != null) {
            ctx.getConfig().clearBed();
        }

        LOGGER.info("Broke existing bed for '{}' at {}", agentName, bedPos);
    }

    /**
     * When a bed with AgentBed NBT is placed, save position and set up dormant agent.
     * If agent already has a bed placed, breaks the old one first.
     */
    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getPlacedBlock().getBlock() instanceof BedBlock)) return;

        // Only handle FOOT part (beds fire event for both FOOT and HEAD)
        if (event.getPlacedBlock().hasProperty(BedBlock.PART)
            && event.getPlacedBlock().getValue(BedBlock.PART) != BedPart.FOOT) {
            return;
        }

        // Check hand items for AgentBed NBT
        ItemStack hand = player.getMainHandItem();
        if (!isAgentBed(hand)) {
            hand = player.getOffhandItem();
            if (!isAgentBed(hand)) return;
        }

        String agentName = hand.getTag().getString(NBT_KEY);
        BlockPos pos = event.getPos();
        String dimension = player.level().dimension().location().toString();

        // If agent already has a different bed placed, break it
        AgentConfig config = AgentConfig.load(agentName);
        if (config.hasBed()) {
            BlockPos oldPos = new BlockPos(config.getBedX(), config.getBedY(), config.getBedZ());
            if (!oldPos.equals(pos)) {
                breakPlacedBed(player, config, agentName);
                config = AgentConfig.load(agentName); // reload after break
            }
        }

        // Save new bed position
        config.setBed(pos.getX(), pos.getY(), pos.getZ(), dimension);
        config.save(agentName);

        // Update in-memory config
        AgentContext ctx = AgentManager.getInstance().getAgent(agentName);
        if (ctx != null) {
            ctx.getConfig().setBed(pos.getX(), pos.getY(), pos.getZ(), dimension);
        }

        // Spawn dormant agent at new bed
        if (player.level() instanceof ServerLevel level) {
            AgentManager.getInstance().spawnDormant(agentName, level);
        }

        player.displayClientMessage(
            Component.literal("[" + agentName + "] bed set at (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")")
                .withStyle(s -> s.withColor(0x55FF55)),
            false);

        LOGGER.info("Agent bed for '{}' placed at {} ({})", agentName, pos, dimension);
    }

    private static boolean isAgentBed(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.hasTag()
            && stack.getTag().contains(NBT_KEY);
    }
}
