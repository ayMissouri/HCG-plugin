package dev.amissouri.hcg.menu;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class MenuItem {

    interface Click {
        void run(Player player, boolean left);
    }

    private final Supplier<ItemStack> render;
    private final Click click;

    private MenuItem(Supplier<ItemStack> render, Click click) {
        this.render = render;
        this.click = click;
    }

    ItemStack render() {
        return render.get();
    }

    void click(Player player, boolean left) {
        if (click != null) {
            click.run(player, left);
        }
    }

    public static MenuItem toggle(String label, Material icon, List<String> description,
                                  BooleanSupplier state, Consumer<Boolean> onToggle) {
        return new MenuItem(
                () -> MenuRender.toggleIcon(label, icon, description, state.getAsBoolean()),
                (player, left) -> onToggle.accept(!state.getAsBoolean()));
    }

    public static MenuItem choice(String label, Material icon, List<String> description,
                                  Supplier<String> value, Consumer<Boolean> onCycle) {
        return new MenuItem(
                () -> MenuRender.valueIcon(label, icon, description, value.get(), MenuRender.HINT_CYCLE),
                (player, left) -> onCycle.accept(left));
    }

    public static MenuItem stepper(String label, Material icon, List<String> description,
                                   Supplier<String> value, Consumer<Boolean> onStep) {
        return new MenuItem(
                () -> MenuRender.valueIcon(label, icon, description, value.get(), MenuRender.HINT_STEP),
                (player, left) -> onStep.accept(left));
    }

    public static MenuItem button(String label, NamedTextColor color, Material icon,
                                  List<String> description, Consumer<Player> onClick) {
        return new MenuItem(
                () -> MenuRender.buttonIcon(label, color, icon, description),
                (player, left) -> onClick.accept(player));
    }

    public static MenuItem display(String label, NamedTextColor color, Material icon, List<String> lore) {
        return new MenuItem(() -> MenuRender.displayIcon(label, color, icon, lore), null);
    }
}
