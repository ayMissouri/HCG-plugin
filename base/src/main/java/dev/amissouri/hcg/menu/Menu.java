package dev.amissouri.hcg.menu;

import java.util.HashMap;
import java.util.Map;

import dev.amissouri.hcg.HcgScheduler;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

public final class Menu {

    @FunctionalInterface
    public interface Content {
        void build(Menu menu);
    }

    static final class Holder implements InventoryHolder {
        Menu menu;
        Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final int SIZE = 36;
    private static final int HEADER_SLOT = 4;
    private static final int[] CONTENT_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};

    private static HcgScheduler scheduler;

    static void scheduler(HcgScheduler value) {
        scheduler = value;
    }

    private final Content content;
    private final Holder holder = new Holder();
    private final Map<Integer, MenuItem> items = new HashMap<>();
    private int cursor;

    private Menu(Component title, Content content) {
        this.content = content;
        this.holder.menu = this;
        this.holder.inventory = Bukkit.createInventory(holder, SIZE, title);
    }

    public static void open(Player player, String title, Content content) {
        Menu menu = new Menu(MenuRender.title(title), content);
        menu.build();
        player.openInventory(menu.holder.inventory);
    }

    public Menu header(MenuItem item) {
        set(HEADER_SLOT, item);
        return this;
    }

    public Menu add(MenuItem item) {
        if (cursor < CONTENT_SLOTS.length) {
            set(CONTENT_SLOTS[cursor++], item);
        }
        return this;
    }

    private void set(int slot, MenuItem item) {
        items.put(slot, item);
        holder.inventory.setItem(slot, item.render());
    }

    void handleClick(Player player, int slot, boolean left) {
        MenuItem item = items.get(slot);
        if (item == null) {
            return;
        }
        item.click(player, left);
        build();
        if (scheduler != null) {
            scheduler.global(() -> scheduler.entity(player, this::build));
        }
    }

    private void build() {
        items.clear();
        cursor = 0;
        ItemStack filler = MenuRender.filler();
        for (int slot = 0; slot < SIZE; slot++) {
            holder.inventory.setItem(slot, filler);
        }
        content.build(this);
    }
}
