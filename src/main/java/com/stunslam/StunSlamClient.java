package com.stunslam;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

import java.util.function.Predicate;

public class StunSlamClient implements ClientModInitializer {
    /**
     * true  = attribute swap: the axe hit goes out the same tick you swap to it (judged with the item you
     *         swapped FROM), and the mace hit goes out after SWAP DELAY ticks without waiting for its meter.
     * false = safe mode: swap, wait for the attack meter to fill before each hit (works on servers
     *         that block attribute swapping).
     */
    private static final boolean ATTRIBUTE_SWAP = true;

    /** Highest value the swap-delay keybind cycles to (it wraps back to 1). */
    private static final int MAX_DELAY = 5;
    /** Ticks to stay on the mace after the smash before swapping back. */
    private static final int SWAP_BACK_DELAY = 2;
    /** Safe mode only: give up if the attack meter never fills (ticks). */
    private static final int MAX_WAIT = 80;
    /** A tap of the key stays "queued" this long if the combo can't start yet (ticks). */
    private static final int PENDING_TIMEOUT = 10;

    private enum Phase { IDLE, AXE_WAIT, AXE_HIT, MACE_HIT }

    private static KeyMapping slamKey;
    private static KeyMapping delayKey;

    private Phase phase = Phase.IDLE;
    private int ticks = 0;
    private int pendingTicks = 0;
    private int previousSlot = 0;
    private int axeSlot = -1;
    private int maceSlot = -1;

    /** Ticks between the axe hit and the mace swap+hit. Changed in-game with the delay keybind. */
    private int swapDelay = 1;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category =
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("stunslam", "main"));

        slamKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.stunslam.go", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, category));
        delayKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.stunslam.delay", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, category));

        // START of tick, so the slot change and the click land in the same game tick.
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.player == null) { phase = Phase.IDLE; pendingTicks = 0; return; }
            Inventory inv = client.player.getInventory();

            // Cycle the swap delay: 1 -> 2 -> ... -> MAX_DELAY -> 1
            while (delayKey.consumeClick()) {
                swapDelay = swapDelay % MAX_DELAY + 1;
          client.player.displayClientMessage(
        Component.literal("Stun Slam swap delay: " + swapDelay + " tick(s)"), true);}

            boolean pressed = false;
            while (slamKey.consumeClick()) pressed = true;
            if (pressed) pendingTicks = PENDING_TIMEOUT;
            boolean want = pendingTicks > 0;
            if (pendingTicks > 0) pendingTicks--;

            if (phase == Phase.IDLE) {
                if (!want) return;
                if (!targetingEntity(client)) return; // stay queued until the timeout
                axeSlot = findSlot(inv, path -> path.endsWith("_axe"));
                maceSlot = findSlot(inv, path -> path.equals("mace"));
                if (axeSlot < 0 || maceSlot < 0) { pendingTicks = 0; return; } // need both in hotbar

                if (ATTRIBUTE_SWAP) {
                    // Wait until the item in hand has a full attack meter.
                    if (client.player.getAttackStrengthScale(0.0F) < 1.0F) return;
                    previousSlot = inv.getSelectedSlot();
                    inv.setSelectedSlot(axeSlot);
                    clickAttack(client);          // axe hit in the same tick: disables the shield
                    ticks = 0;
                    pendingTicks = 0;
                    phase = Phase.AXE_HIT;
                } else {
                    previousSlot = inv.getSelectedSlot();
                    inv.setSelectedSlot(axeSlot);
                    ticks = 0;
                    pendingTicks = 0;
                    phase = Phase.AXE_WAIT;
                }
            } else if (phase == Phase.AXE_WAIT) {
                ticks++;
                if (ticks >= 1 && client.player.getAttackStrengthScale(0.0F) >= 1.0F) {
                    clickAttack(client);
                    ticks = 0;
                    phase = Phase.AXE_HIT;
                } else if (ticks > MAX_WAIT) {
                    restore(inv);
                }
            } else if (phase == Phase.AXE_HIT) {
                ticks++;
                if (ticks < swapDelay) return;
                if (!targetingEntity(client)) { restore(inv); return; }
                if (!ATTRIBUTE_SWAP && client.player.getAttackStrengthScale(0.0F) < 1.0F) {
                    if (ticks > MAX_WAIT) restore(inv);
                    return;
                }
                inv.setSelectedSlot(maceSlot);
                clickAttack(client);              // mace smash
                ticks = 0;
                phase = Phase.MACE_HIT;
            } else if (phase == Phase.MACE_HIT) {
                if (++ticks >= SWAP_BACK_DELAY) restore(inv);
            }
        });
    }

    private void restore(Inventory inv) {
        inv.setSelectedSlot(previousSlot);
        phase = Phase.IDLE;
    }

    private static boolean targetingEntity(Minecraft client) {
        return client.hitResult != null && client.hitResult.getType() == HitResult.Type.ENTITY;
    }

    private static void clickAttack(Minecraft client) {
        KeyMapping.click(InputConstants.getKey(client.options.keyAttack.saveString()));
    }

    /** First hotbar slot whose item registry path matches; -1 if none. */
    private static int findSlot(Inventory inv, Predicate<String> pathMatches) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (pathMatches.test(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath())) return i;
        }
        return -1;
    }
}
