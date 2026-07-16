package dev.amissouri.hcg.healthdecay;
import dev.amissouri.hcg.HcgText;
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

public final class DecayCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("on", "off", "status", "restore", "interval", "amount");

    private static final long INTERVAL_STEP = 10;
    private static final long INTERVAL_MIN = 5;
    private static final double AMOUNT_STEP = 0.5;
    private static final double AMOUNT_MIN = 0.5;
    private static final double AMOUNT_MAX = 10.0;

    private final JavaPlugin plugin;
    private final DecayManager decayManager;

    public DecayCommand(JavaPlugin plugin, DecayManager decayManager) {
        this.plugin = plugin;
        this.decayManager = decayManager;
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
            case "restore" -> {
                restore();
                Messages.send(sender, "healthdecay.restored");
            }
            case "interval" -> {
                Long seconds = parsePositive(args, sender, "seconds");
                if (seconds != null) {
                    setInterval(seconds);
                    Messages.send(sender, "healthdecay.interval-set", "seconds", String.valueOf(seconds));
                }
            }
            case "amount" -> {
                Double hearts = parsePositiveDouble(args, sender, "hearts");
                if (hearts != null) {
                    setAmount(hearts);
                    Messages.send(sender, "healthdecay.amount-set", "hearts", String.valueOf(hearts));
                }
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
        Menu.open(player, "Health Decay", menu -> {
            menu.header(MenuItem.toggle("Health Decay", Material.WITHER_ROSE,
                    List.of("Max health ticks down over time.",
                            "A confirmed PvP kill restores everyone."),
                    decayManager::isRunning,
                    on -> {
                        if (on) {
                            decayManager.start();
                            Messages.broadcastOps("healthdecay.start-broadcast",
                                    "hearts", HcgText.formatHearts(decayManager.minimumHp()));
                        } else {
                            decayManager.stop();
                            Messages.broadcastOps("healthdecay.stop-broadcast");
                        }
                    }));

            menu.add(MenuItem.display("Max health", NamedTextColor.RED, Material.GOLDEN_APPLE,
                    List.of(HcgText.formatHearts(decayManager.currentMaxHp()) + " / "
                                    + HcgText.formatHearts(decayManager.maximumHp()) + " hearts",
                            "Floor: " + HcgText.formatHearts(decayManager.minimumHp()) + " hearts")));

            menu.add(MenuItem.button("Restore health", NamedTextColor.GREEN, Material.TOTEM_OF_UNDYING,
                    List.of("Reset everyone to full and", "restart the decay timer."),
                    clicker -> {
                        restore();
                        Messages.send(clicker, "healthdecay.restored");
                    }));

            menu.add(MenuItem.stepper("Decay interval", Material.CLOCK,
                    List.of("Seconds between each decay tick."),
                    () -> plugin.getConfig().getLong("decay.interval-seconds", 60) + "s",
                    forward -> {
                        long interval = plugin.getConfig().getLong("decay.interval-seconds", 60);
                        setInterval(forward ? interval + INTERVAL_STEP
                                : Math.max(INTERVAL_MIN, interval - INTERVAL_STEP));
                    }));

            menu.add(MenuItem.stepper("Decay amount", Material.REDSTONE,
                    List.of("Hearts lost per decay tick."),
                    () -> fmt(plugin.getConfig().getDouble("decay.amount-hearts", 0.5)) + " heart(s)",
                    forward -> {
                        double amount = plugin.getConfig().getDouble("decay.amount-hearts", 0.5);
                        setAmount(forward ? Math.min(AMOUNT_MAX, amount + AMOUNT_STEP)
                                : Math.max(AMOUNT_MIN, amount - AMOUNT_STEP));
                    }));
        });
    }

    private void turnOn(CommandSender sender) {
        if (decayManager.isRunning()) {
            Messages.send(sender, "healthdecay.already-running");
            return;
        }
        decayManager.start();
        Messages.broadcastOps("healthdecay.start-broadcast",
                "hearts", HcgText.formatHearts(decayManager.minimumHp()));
    }

    private void turnOff(CommandSender sender) {
        if (!decayManager.isRunning()) {
            Messages.send(sender, "healthdecay.not-running");
            return;
        }
        decayManager.stop();
        Messages.broadcastOps("healthdecay.stop-broadcast");
    }

    private void restore() {
        decayManager.resetHealth();
        decayManager.restartTimer();
    }

    private void setInterval(long seconds) {
        plugin.getConfig().set("decay.interval-seconds", seconds);
        plugin.saveConfig();
        decayManager.restartTimer();
    }

    private void setAmount(double hearts) {
        plugin.getConfig().set("decay.amount-hearts", hearts);
        plugin.saveConfig();
    }

    private void sendStatus(CommandSender sender) {
        Messages.send(sender, "healthdecay.status-state",
                "state", decayManager.isRunning() ? "running" : "stopped");
        Messages.send(sender, "healthdecay.status-health",
                "current", HcgText.formatHearts(decayManager.currentMaxHp()),
                "floor", HcgText.formatHearts(decayManager.minimumHp()));
        Messages.send(sender, "healthdecay.status-rate",
                "amount", HcgText.formatHearts(decayManager.decayAmountHp()),
                "seconds", String.valueOf(plugin.getConfig().getLong("decay.interval-seconds", 60)));
    }

    private static String fmt(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private Long parsePositive(String[] args, CommandSender sender, String name) {
        if (args.length < 2) {
            Messages.send(sender, "general.missing-argument", "name", name);
            return null;
        }
        try {
            long value = Long.parseLong(args[1]);
            if (value <= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            Messages.send(sender, "general.not-positive", "input", args[1]);
            return null;
        }
    }

    private Double parsePositiveDouble(String[] args, CommandSender sender, String name) {
        if (args.length < 2) {
            Messages.send(sender, "general.missing-argument", "name", name);
            return null;
        }
        try {
            double value = Double.parseDouble(args[1]);
            if (value <= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            Messages.send(sender, "general.not-positive", "input", args[1]);
            return null;
        }
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
