package dev.amissouri.hcg.graves;
import dev.amissouri.hcg.Messages;
import dev.amissouri.hcg.menu.Menu;
import dev.amissouri.hcg.menu.MenuItem;

import java.util.List;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class GravesCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("on", "off", "status", "remove");
    private static final int REMOVE_RANGE = 20;

    private final GravesManager manager;

    public GravesCommand(GravesManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            openOrStatus(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "menu" -> openOrStatus(sender);
            case "on" -> turnOn(sender);
            case "off" -> turnOff(sender);
            case "status" -> sendStatus(sender);
            case "remove" -> removeTarget(sender);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void openOrStatus(CommandSender sender) {
        if (sender instanceof Player player) {
            openMenu(player);
        } else {
            sendStatus(sender);
        }
    }

    private void openMenu(Player player) {
        Menu.open(player, "Graves", menu -> {
            menu.header(MenuItem.toggle("Graves", Material.PLAYER_HEAD,
                    List.of("On death a head stores the victim's",
                            "items and full XP; only the owner collects it."),
                    manager::isEnabled,
                    on -> {
                        manager.setEnabled(on);
                        Messages.broadcastOps(on
                                ? "graves.enabled-broadcast" : "graves.disabled-broadcast");
                    }));

            menu.add(MenuItem.display("Graves placed", NamedTextColor.YELLOW, Material.CHEST,
                    List.of(manager.count() + " in the world.",
                            "Use /graves remove while looking",
                            "at one to force it open.")));
        });
    }

    private void turnOn(CommandSender sender) {
        if (manager.isEnabled()) {
            Messages.send(sender, "graves.already-enabled");
            return;
        }
        manager.setEnabled(true);
        Messages.broadcastOps("graves.enabled-broadcast");
    }

    private void turnOff(CommandSender sender) {
        if (!manager.isEnabled()) {
            Messages.send(sender, "graves.not-enabled");
            return;
        }
        manager.setEnabled(false);
        Messages.broadcastOps("graves.disabled-broadcast");
    }

    private void removeTarget(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Messages.send(sender, "general.only-players");
            return;
        }
        Block target = player.getTargetBlockExact(REMOVE_RANGE);
        GravesManager.Grave grave = manager.graveAt(target);
        if (grave == null) {
            Messages.send(sender, "graves.no-grave-in-sight", "range", String.valueOf(REMOVE_RANGE));
            return;
        }
        manager.forceRemove(target);
        Messages.send(sender, "graves.removed", "owner", grave.ownerName());
    }

    private void sendStatus(CommandSender sender) {
        Messages.send(sender, "graves.status",
                "state", manager.isEnabled() ? "enabled" : "disabled",
                "count", String.valueOf(manager.count()));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
