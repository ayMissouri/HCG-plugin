package dev.amissouri.hcg.menu;

import java.util.ArrayList;
import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

final class MenuRender {

    static final Component HINT_TOGGLE = hint("Left-click", " to toggle");
    static final Component HINT_STEP = twoPart("Left-click", " to raise, ", "right-click", " to lower");
    static final Component HINT_CYCLE = twoPart("Left-click", " next, ", "right-click", " back");
    static final Component HINT_CLICK = hint("Click", " to run");

    private MenuRender() {
    }

    static Component title(String title) {
        return text(title, NamedTextColor.DARK_GRAY);
    }

    static ItemStack filler() {
        return make(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), List.of());
    }

    static ItemStack toggleIcon(String label, Material material, List<String> description, boolean on) {
        List<Component> lore = describe(description);
        lore.add(statusLine(on));
        lore.add(Component.empty());
        lore.add(HINT_TOGGLE);
        return make(material, text(label, on ? NamedTextColor.GREEN : NamedTextColor.RED)
                .decorate(TextDecoration.BOLD), lore);
    }

    static ItemStack valueIcon(String label, Material material, List<String> description,
                               String value, Component hintLine) {
        List<Component> lore = describe(description);
        lore.add(text("Value: ", NamedTextColor.GRAY)
                .append(text(value, NamedTextColor.YELLOW).decorate(TextDecoration.BOLD)));
        lore.add(Component.empty());
        lore.add(hintLine);
        return make(material, text(label, NamedTextColor.AQUA).decorate(TextDecoration.BOLD), lore);
    }

    static ItemStack buttonIcon(String label, NamedTextColor color, Material material,
                                List<String> description) {
        List<Component> lore = describe(description);
        lore.add(HINT_CLICK);
        return make(material, text(label, color).decorate(TextDecoration.BOLD), lore);
    }

    static ItemStack displayIcon(String label, NamedTextColor color, Material material,
                                 List<String> lines) {
        return make(material, text(label, color).decorate(TextDecoration.BOLD), plain(lines));
    }

    private static List<Component> describe(List<String> lines) {
        List<Component> lore = plain(lines);
        if (!lore.isEmpty()) {
            lore.add(Component.empty());
        }
        return lore;
    }

    private static List<Component> plain(List<String> lines) {
        List<Component> lore = new ArrayList<>();
        for (String line : lines) {
            lore.add(text(line, NamedTextColor.GRAY));
        }
        return lore;
    }

    private static Component statusLine(boolean on) {
        return text("Status: ", NamedTextColor.GRAY).append(on
                ? text("ENABLED", NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
                : text("DISABLED", NamedTextColor.RED).decorate(TextDecoration.BOLD));
    }

    private static Component hint(String action, String rest) {
        return text(action, NamedTextColor.YELLOW).append(text(rest, NamedTextColor.GRAY));
    }

    private static Component twoPart(String a1, String r1, String a2, String r2) {
        return hint(a1, r1).append(hint(a2, r2));
    }

    private static Component text(String value, NamedTextColor color) {
        return Component.text(value, color).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack make(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        if (!lore.isEmpty()) {
            meta.lore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }
}
