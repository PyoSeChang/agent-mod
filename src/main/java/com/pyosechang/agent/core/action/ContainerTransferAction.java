package com.pyosechang.agent.core.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Moves multiple items between slots in an open container.
 * Each move: {from, to?} — omit 'to' for quick_move (auto-placement).
 * Continues on partial failure; each move gets an individual result.
 */
public class ContainerTransferAction implements Action {
    @Override
    public String getName() { return "container_transfer"; }

    @Override
    public JsonObject execute(FakePlayer agent, JsonObject params) {
        if (agent.containerMenu == agent.inventoryMenu) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", "No container open");
            return result;
        }

        JsonArray moves = params.getAsJsonArray("moves");
        JsonArray results = new JsonArray();

        for (JsonElement elem : moves) {
            JsonObject move = elem.getAsJsonObject();
            int from = move.get("from").getAsInt();
            boolean hasTo = move.has("to") && !move.get("to").isJsonNull();

            JsonObject moveResult = new JsonObject();
            moveResult.addProperty("from", from);

            // Validate from slot
            if (from < 0 || from >= agent.containerMenu.slots.size()) {
                moveResult.addProperty("error", "invalid_slot");
                results.add(moveResult);
                continue;
            }

            ItemStack sourceStack = agent.containerMenu.slots.get(from).getItem();
            if (sourceStack.isEmpty()) {
                moveResult.addProperty("error", "empty_slot");
                results.add(moveResult);
                continue;
            }

            String itemId = ForgeRegistries.ITEMS.getKey(sourceStack.getItem()).toString();
            int countBefore = sourceStack.getCount();

            if (!hasTo) {
                // Quick move (shift-click) — destination is auto-determined
                agent.containerMenu.clicked(from, 0, ClickType.QUICK_MOVE, agent);
                agent.containerMenu.broadcastChanges();

                ItemStack afterStack = agent.containerMenu.slots.get(from).getItem();
                int moved = countBefore - (afterStack.isEmpty() ? 0 : afterStack.getCount());

                if (moved > 0) {
                    moveResult.addProperty("item", itemId);
                    moveResult.addProperty("count", moved);
                } else {
                    moveResult.addProperty("error", "no_space");
                }
            } else {
                int to = move.get("to").getAsInt();
                moveResult.addProperty("to", to);

                // Validate to slot
                if (to < 0 || to >= agent.containerMenu.slots.size()) {
                    moveResult.addProperty("error", "invalid_slot");
                    results.add(moveResult);
                    continue;
                }

                // Pickup from source
                agent.containerMenu.clicked(from, 0, ClickType.PICKUP, agent);
                agent.containerMenu.broadcastChanges();

                ItemStack carried = agent.containerMenu.getCarried();
                if (carried.isEmpty()) {
                    moveResult.addProperty("error", "pickup_failed");
                    results.add(moveResult);
                    continue;
                }

                // Place at destination
                agent.containerMenu.clicked(to, 0, ClickType.PICKUP, agent);
                agent.containerMenu.broadcastChanges();

                ItemStack stillCarried = agent.containerMenu.getCarried();
                if (!stillCarried.isEmpty()) {
                    // Destination was occupied and swapped — put the swapped item back
                    agent.containerMenu.clicked(from, 0, ClickType.PICKUP, agent);
                    agent.containerMenu.broadcastChanges();

                    // Check if we still have something carried (shouldn't normally)
                    if (!agent.containerMenu.getCarried().isEmpty()) {
                        // Drop it to avoid stuck state
                        agent.drop(agent.containerMenu.getCarried(), false);
                        agent.containerMenu.setCarried(ItemStack.EMPTY);
                    }

                    moveResult.addProperty("error", "slot_occupied");
                    String occupant = ForgeRegistries.ITEMS.getKey(stillCarried.getItem()).toString();
                    moveResult.addProperty("occupant", occupant);
                } else {
                    moveResult.addProperty("item", itemId);
                    moveResult.addProperty("count", countBefore);
                }
            }

            results.add(moveResult);
        }

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.add("results", results);
        return result;
    }
}
