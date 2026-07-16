package dev.amissouri.hcg.lavaraise;
import dev.amissouri.hcg.Messages;
import dev.amissouri.hcg.menu.Menu;
import dev.amissouri.hcg.menu.MenuItem;

import java.util.List;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class VolcanoCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("setcenter", "erupt", "stop", "schedule", "duration", "radius", "shake", "status");

    private static final int DAY_TICKS = 24000;
    private static final int TIME_STEP = 1000;

    private final VolcanoManager manager;

    public VolcanoCommand(VolcanoManager manager) {
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
            case "setcenter" -> {
                if (!(sender instanceof Player player)) {
                    Messages.send(sender, "general.only-players");
                    return true;
                }
                Block target = player.getTargetBlockExact(100);
                Location center = target != null
                        ? target.getLocation().add(0, 1, 0)
                        : player.getLocation();
                setCenter(sender, center);
            }
            case "erupt" -> {
                int seconds = manager.durationSeconds();
                if (args.length >= 2) {
                    try {
                        seconds = Math.clamp(Integer.parseInt(args[1]), 3, 600);
                    } catch (NumberFormatException e) {
                        Messages.send(sender, "volcano.invalid-seconds", "input", args[1]);
                        return true;
                    }
                }
                if (manager.center() == null) {
                    Messages.send(sender, "volcano.no-center");
                } else if (!manager.erupt(seconds)) {
                    Messages.send(sender, "volcano.already-erupting");
                }
            }
            case "stop" -> {
                if (manager.isErupting()) {
                    manager.stop();
                } else {
                    Messages.send(sender, "volcano.not-erupting");
                }
            }
            case "schedule" -> {
                if (args.length < 2) {
                    return false;
                }
                if (args[1].equalsIgnoreCase("off")) {
                    manager.disableSchedule();
                    Messages.send(sender, "volcano.schedule-off");
                    return true;
                }
                int ticks = LavaRaiseManager.parseTime(args[1]);
                if (ticks < 0) {
                    Messages.send(sender, "volcano.invalid-time", "input", args[1]);
                    return true;
                }
                manager.setSchedule(ticks);
                Messages.send(sender, "volcano.schedule-set", "time", LavaRaiseManager.formatTime(ticks));
                if (manager.center() == null) {
                    Messages.send(sender, "volcano.schedule-no-center");
                }
            }
            case "duration" -> {
                if (args.length < 2) {
                    return false;
                }
                try {
                    int seconds = Math.clamp(Integer.parseInt(args[1]), 3, 600);
                    manager.setDurationSeconds(seconds);
                    Messages.send(sender, "volcano.duration-set", "seconds", String.valueOf(seconds));
                } catch (NumberFormatException e) {
                    Messages.send(sender, "general.not-a-number", "input", args[1]);
                }
            }
            case "radius" -> {
                if (args.length < 2) {
                    return false;
                }
                try {
                    int blocks = Math.clamp(Integer.parseInt(args[1]), 10, 1000);
                    manager.setShakeRadius(blocks);
                    Messages.send(sender, "volcano.radius-set", "blocks", String.valueOf(blocks));
                } catch (NumberFormatException e) {
                    Messages.send(sender, "general.not-a-number", "input", args[1]);
                }
            }
            case "shake" -> {
                if (args.length < 2 || !(args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
                    return false;
                }
                boolean on = args[1].equalsIgnoreCase("on");
                manager.setShakeEnabled(on);
                Messages.send(sender, "volcano.shake-set", "state", on ? "on" : "off");
            }
            case "status" -> sendStatus(sender);
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
        Menu.open(player, "Volcano", menu -> {
            boolean erupting = manager.isErupting();
            Location center = manager.center();
            String where = center == null ? "not set"
                    : center.getBlockX() + ", " + center.getBlockY() + ", " + center.getBlockZ();
            menu.header(MenuItem.display("Volcano", erupting ? NamedTextColor.DARK_RED : NamedTextColor.GREEN,
                    Material.MAGMA_BLOCK,
                    List.of("Status: " + (erupting ? "ERUPTING" : "quiet"), "Center: " + where)));

            if (erupting) {
                menu.add(MenuItem.button("Stop eruption", NamedTextColor.RED, Material.BARRIER,
                        List.of("Calm the volcano immediately."),
                        clicker -> manager.stop()));
            } else {
                menu.add(MenuItem.button("Erupt now", NamedTextColor.GOLD, Material.FIRE_CHARGE,
                        List.of("Trigger an eruption at the crater."),
                        clicker -> {
                            if (manager.center() == null) {
                                Messages.send(clicker, "volcano.no-center");
                            } else {
                                manager.erupt(manager.durationSeconds());
                            }
                        }));
            }

            menu.add(MenuItem.button("Set center here", NamedTextColor.AQUA, Material.COMPASS,
                    List.of("Mark the crater at your position."),
                    clicker -> setCenter(clicker, clicker.getLocation())));

            menu.add(MenuItem.stepper("Duration", Material.CLOCK,
                    List.of("How long an eruption lasts."),
                    () -> manager.durationSeconds() + "s",
                    forward -> {
                        int seconds = manager.durationSeconds();
                        manager.setDurationSeconds(
                                forward ? Math.min(600, seconds + 5) : Math.max(3, seconds - 5));
                    }));

            menu.add(MenuItem.toggle("Daily schedule", Material.RECOVERY_COMPASS,
                    List.of("Erupt automatically at a set world time."),
                    manager::isScheduled,
                    on -> {
                        if (on) {
                            manager.setSchedule(manager.eruptTime());
                        } else {
                            manager.disableSchedule();
                        }
                    }));

            if (manager.isScheduled()) {
                menu.add(MenuItem.stepper("Schedule time", Material.CLOCK,
                        List.of("World time of the daily eruption."),
                        () -> LavaRaiseManager.formatTime(manager.eruptTime()),
                        forward -> manager.setSchedule(stepTime(manager.eruptTime(), forward))));
            }

            menu.add(MenuItem.toggle("Screen shake", Material.BELL,
                    List.of("Eruptions shake nearby players' screens."),
                    manager::isShakeEnabled, manager::setShakeEnabled));

            menu.add(MenuItem.stepper("Shake radius", Material.REDSTONE,
                    List.of("How far the screen shake reaches."),
                    () -> manager.shakeRadius() + " blocks",
                    forward -> {
                        int blocks = manager.shakeRadius();
                        manager.setShakeRadius(
                                forward ? Math.min(1000, blocks + 25) : Math.max(10, blocks - 25));
                    }));
        });
    }

    private void setCenter(CommandSender sender, Location center) {
        manager.setCenter(center);
        Messages.send(sender, "volcano.center-set",
                "x", String.valueOf(center.getBlockX()),
                "y", String.valueOf(center.getBlockY()),
                "z", String.valueOf(center.getBlockZ()),
                "world", center.getWorld().getName());
    }

    private static int stepTime(int ticks, boolean forward) {
        return Math.floorMod(ticks + (forward ? TIME_STEP : -TIME_STEP), DAY_TICKS);
    }

    private void sendStatus(CommandSender sender) {
        Location center = manager.center();
        String where = center == null ? "not set"
                : center.getBlockX() + ", " + center.getBlockY() + ", " + center.getBlockZ()
                        + " (" + center.getWorld().getName() + ")";
        Messages.send(sender, "volcano.status",
                "state", manager.isErupting() ? "ERUPTING" : "quiet",
                "center", where,
                "seconds", String.valueOf(manager.durationSeconds()));
        Messages.send(sender, manager.isScheduled()
                        ? "volcano.status-schedule-on" : "volcano.status-schedule-off",
                "time", LavaRaiseManager.formatTime(manager.eruptTime()));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("erupt") || args[0].equalsIgnoreCase("duration"))) {
            return List.of("10", "20", "30", "60").stream()
                    .filter(s -> s.startsWith(args[1]))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("radius")) {
            return List.of("100", "150", "200", "300", "500").stream()
                    .filter(s -> s.startsWith(args[1]))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("shake")) {
            return List.of("on", "off").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("schedule")) {
            return List.of("off", "day", "noon", "sunset", "night", "midnight", "sunrise").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
