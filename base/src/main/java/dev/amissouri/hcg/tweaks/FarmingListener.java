package dev.amissouri.hcg.tweaks;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public final class FarmingListener implements Listener {

    private final FarmingTweak tweak;

    public FarmingListener(FarmingTweak tweak) {
        this.tweak = tweak;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getHand() != EquipmentSlot.HAND
                || event.useInteractedBlock() == Event.Result.DENY) {
            return;
        }
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!tweak.canRightClickHarvest(player, block, tool)) {
            return;
        }
        event.setCancelled(true);
        tweak.harvest(player, block, tool, true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        if (!tweak.canHarvestOnBreak(player, block)) {
            return;
        }
        if (tweak.replantOnBreak()) {
            event.setCancelled(true);
            tweak.harvest(player, block, player.getInventory().getItemInMainHand(), false);
        } else {
            tweak.giveCropXp(block);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking()) {
            tweak.twerk(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        tweak.forget(event.getPlayer().getUniqueId());
    }
}
