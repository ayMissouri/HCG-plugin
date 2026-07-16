package dev.amissouri.hcg.randomdrops;
import dev.amissouri.hcg.Messages;
import dev.amissouri.hcg.menu.Menu;
import dev.amissouri.hcg.menu.MenuItem;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class RandomDropsCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("on", "off", "status", "mode", "enchants", "mobs", "reroll");

    private final JavaPlugin plugin;
    private final RandomDropsManager manager;

    public RandomDropsCommand(JavaPlugin plugin, RandomDropsManager manager) {
        this.plugin = plugin;
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
            case "mode" -> {
                if (!setModeFromArg(sender, args)) {
                    Messages.send(sender, "randomdrops.mode-usage");
                }
            }
            case "enchants" -> {
                if (!setEnchantsFromArg(sender, args)) {
                    Messages.send(sender, "randomdrops.enchants-usage");
                }
            }
            case "mobs" -> {
                if (!setMobsFromArg(sender, args)) {
                    Messages.send(sender, "randomdrops.mobs-usage");
                }
            }
            case "reroll" -> {
                manager.reroll();
                Messages.send(sender, "randomdrops.rerolled");
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
            sendStatus(sender);
        }
    }

    private void openMenu(Player player) {
        Menu.open(player, "Random Drops", menu -> {
            menu.header(MenuItem.toggle("Random Drops", Material.DROPPER,
                    List.of("Every block break drops a random item.",
                            manager.poolSize() + " items in the pool."),
                    manager::isEnabled,
                    on -> {
                        manager.setEnabled(on);
                        if (on) {
                            Messages.broadcastOps("randomdrops.enabled-broadcast",
                                    "mode", manager.mode().name().toLowerCase());
                        } else {
                            Messages.broadcastOps("randomdrops.disabled-broadcast");
                        }
                    }));

            menu.add(MenuItem.choice("Mode", Material.COMPARATOR,
                    List.of("DYNAMIC: every break rolls fresh.",
                            "STATIC: each block keeps one drop."),
                    () -> manager.mode() == RandomDropsManager.Mode.STATIC ? "STATIC" : "DYNAMIC",
                    forward -> manager.setMode(manager.mode() == RandomDropsManager.Mode.STATIC
                            ? RandomDropsManager.Mode.DYNAMIC : RandomDropsManager.Mode.STATIC)));

            menu.add(MenuItem.toggle("Enchanted drops", Material.ENCHANTED_BOOK,
                    List.of("Give each drop 1-3 random enchantments."),
                    manager::isEnchanted, manager::setEnchanted));

            menu.add(MenuItem.toggle("Mob drops", Material.ZOMBIE_HEAD,
                    List.of("Every mob death drops a random item too."),
                    manager::isMobsEnabled, manager::setMobsEnabled));

            if (manager.mode() == RandomDropsManager.Mode.STATIC) {
                menu.add(MenuItem.button("Reroll drop table", NamedTextColor.LIGHT_PURPLE,
                        Material.BONE_MEAL,
                        List.of("Randomize every block's fixed drop."),
                        clicker -> {
                            manager.reroll();
                            Messages.send(clicker, "randomdrops.rerolled");
                        }));
            }
        });
    }

    private boolean setModeFromArg(CommandSender sender, String[] args) {
        String value = args[args.length - 1].toLowerCase();
        RandomDropsManager.Mode mode = switch (value) {
            case "dynamic" -> RandomDropsManager.Mode.DYNAMIC;
            case "static" -> RandomDropsManager.Mode.STATIC;
            default -> null;
        };
        if (mode == null) {
            return false;
        }
        if (manager.mode() != mode) {
            manager.setMode(mode);
            Messages.send(sender, "randomdrops.mode-set", "mode", value);
        }
        return true;
    }

    private boolean setEnchantsFromArg(CommandSender sender, String[] args) {
        String value = args[args.length - 1].toLowerCase();
        Boolean state = switch (value) {
            case "on" -> true;
            case "off" -> false;
            default -> null;
        };
        if (state == null) {
            return false;
        }
        if (manager.isEnchanted() != state) {
            manager.setEnchanted(state);
            Messages.send(sender, "randomdrops.enchants-set", "state", value);
        }
        return true;
    }

    private boolean setMobsFromArg(CommandSender sender, String[] args) {
        String value = args[args.length - 1].toLowerCase();
        Boolean state = switch (value) {
            case "on" -> true;
            case "off" -> false;
            default -> null;
        };
        if (state == null) {
            return false;
        }
        if (manager.isMobsEnabled() != state) {
            manager.setMobsEnabled(state);
            Messages.send(sender, "randomdrops.mobs-set", "state", value);
        }
        return true;
    }

    private void turnOn(CommandSender sender) {
        if (manager.isEnabled()) {
            Messages.send(sender, "randomdrops.already-enabled");
            return;
        }
        manager.setEnabled(true);
        Messages.broadcastOps("randomdrops.enabled-broadcast", "mode", manager.mode().name().toLowerCase());
    }

    private void turnOff(CommandSender sender) {
        if (!manager.isEnabled()) {
            Messages.send(sender, "randomdrops.not-enabled");
            return;
        }
        manager.setEnabled(false);
        Messages.broadcastOps("randomdrops.disabled-broadcast");
    }

    private void sendStatus(CommandSender sender) {
        Messages.send(sender, "randomdrops.status",
                "state", manager.isEnabled() ? "enabled" : "disabled",
                "mode", manager.mode().name().toLowerCase(),
                "enchants", manager.isEnchanted() ? "on" : "off",
                "mobs", manager.isMobsEnabled() ? "on" : "off",
                "pool", String.valueOf(manager.poolSize()));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("mode")) {
            return List.of("dynamic", "static").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .toList();
        }
        if (args.length == 2
                && (args[0].equalsIgnoreCase("enchants") || args[0].equalsIgnoreCase("mobs"))) {
            return List.of("on", "off").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
