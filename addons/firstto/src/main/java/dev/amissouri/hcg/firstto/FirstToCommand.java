package dev.amissouri.hcg.firstto;
import dev.amissouri.hcg.Messages;
import dev.amissouri.hcg.menu.Menu;
import dev.amissouri.hcg.menu.MenuItem;

import java.util.List;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** /firstto, a chest menu for players plus craft|obtain|stop|status and nether|end|tpspawn toggles. */
public final class FirstToCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("craft", "obtain", "stop", "status", "nether", "end", "tpspawn");
    private static final List<String> TOGGLES = List.of("nether", "end", "tpspawn");

    private final FirstToManager manager;

    public FirstToCommand(FirstToManager manager) {
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
            case "craft" -> startRound(sender, FirstToManager.Mode.CRAFT);
            case "obtain", "get" -> startRound(sender, FirstToManager.Mode.OBTAIN);
            case "stop" -> stop(sender);
            case "status" -> showStatus(sender);
            case "nether", "end", "tpspawn" -> {
                if (args.length < 2) {
                    return false;
                }
                return toggle(sender, args[0].toLowerCase(), args[1].toLowerCase());
            }
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
            showStatus(sender);
        }
    }

    private void openMenu(Player player) {
        Menu.open(player, "First To", menu -> {
            menu.header(MenuItem.display("First To",
                    manager.isRunning() || manager.isRolling() ? NamedTextColor.GREEN : NamedTextColor.GRAY,
                    Material.TARGET,
                    List.of("Race to be first to craft or obtain",
                            "a randomly rolled item.", roundLine())));

            if (manager.isRunning()) {
                menu.add(MenuItem.button("Stop round", NamedTextColor.RED, Material.BARRIER,
                        List.of("Cancel the current round."),
                        clicker -> stop(clicker)));
            } else if (!manager.isRolling()) {
                menu.add(MenuItem.button("Craft round", NamedTextColor.GREEN, Material.CRAFTING_TABLE,
                        List.of("Roll a craftable item; first to", "craft it wins."),
                        clicker -> startRound(clicker, FirstToManager.Mode.CRAFT)));
                menu.add(MenuItem.button("Obtain round", NamedTextColor.AQUA, Material.CHEST,
                        List.of("Roll any survival item; first to", "get one wins."),
                        clicker -> startRound(clicker, FirstToManager.Mode.OBTAIN)));
            }

            menu.add(MenuItem.toggle("Nether items", Material.NETHERRACK,
                    List.of("Allow nether-only items as targets."),
                    manager::includeNether, manager::setIncludeNether));

            menu.add(MenuItem.toggle("End items", Material.END_STONE,
                    List.of("Allow end-only items as targets."),
                    manager::includeEnd, manager::setIncludeEnd));

            menu.add(MenuItem.toggle("TP to spawn on win", Material.ENDER_PEARL,
                    List.of("Teleport everyone to spawn on a win."),
                    manager::tpSpawnOnWin, manager::setTpSpawnOnWin));
        });
    }

    private String roundLine() {
        if (manager.isRolling()) {
            return "Rolling...";
        }
        if (manager.isRunning()) {
            String action = manager.mode() == FirstToManager.Mode.CRAFT ? "craft" : "obtain";
            return "First to " + action + " " + FirstToManager.prettyName(manager.target());
        }
        return "Idle";
    }

    private void startRound(CommandSender sender, FirstToManager.Mode mode) {
        if (manager.isRunning() || manager.isRolling()) {
            Messages.send(sender, "firstto.already-running");
            return;
        }
        if (!manager.start(mode)) {
            Messages.send(sender, "firstto.pool-empty");
        }
    }

    private void stop(CommandSender sender) {
        if (!manager.isRunning()) {
            Messages.send(sender, "firstto.not-running");
            return;
        }
        manager.stop();
        Messages.broadcast("firstto.stopped-broadcast");
    }

    private boolean toggle(CommandSender sender, String setting, String value) {
        boolean state;
        switch (value) {
            case "on" -> state = true;
            case "off" -> state = false;
            default -> {
                return false;
            }
        }
        switch (setting) {
            case "nether" -> {
                manager.setIncludeNether(state);
                Messages.send(sender, "firstto.nether-set", "state", value);
            }
            case "end" -> {
                manager.setIncludeEnd(state);
                Messages.send(sender, "firstto.end-set", "state", value);
            }
            case "tpspawn" -> {
                manager.setTpSpawnOnWin(state);
                Messages.send(sender, "firstto.tpspawn-set", "state", value);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void showStatus(CommandSender sender) {
        if (manager.isRolling()) {
            Messages.send(sender, "firstto.status-rolling");
        } else if (manager.isRunning()) {
            Messages.send(sender, "firstto.status-running",
                    "action", Messages.raw(manager.mode() == FirstToManager.Mode.CRAFT
                            ? "firstto.action-craft" : "firstto.action-obtain"),
                    "item", FirstToManager.prettyName(manager.target()));
        } else {
            Messages.send(sender, "firstto.status-idle");
        }
        Messages.send(sender, "firstto.status-settings",
                "nether", manager.includeNether() ? "on" : "off",
                "end", manager.includeEnd() ? "on" : "off",
                "tpspawn", manager.tpSpawnOnWin() ? "on" : "off");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && TOGGLES.contains(args[0].toLowerCase())) {
            return List.of("on", "off").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
