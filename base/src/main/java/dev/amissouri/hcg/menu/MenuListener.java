package dev.amissouri.hcg.menu;

import dev.amissouri.hcg.HcgScheduler;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public final class MenuListener implements Listener {

    public MenuListener(HcgScheduler scheduler) {
        Menu.scheduler(scheduler);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu.Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != holder.getInventory()
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        holder.menu.handleClick(player, event.getSlot(), !event.isRightClick());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu.Holder) {
            event.setCancelled(true);
        }
    }
}
