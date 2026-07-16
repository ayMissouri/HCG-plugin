package dev.amissouri.hcg.lavaraise;
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

/** /lavaraise, a chest settings menu for players plus typed subcommands. */
public final class LavaRaiseCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "on", "off", "status", "start", "end", "duration", "maxy", "water",
            "blocks", "mobs", "cancel", "purge");
    private static final List<String> TIME_SUGGESTIONS =
            List.of("day", "noon", "sunset", "night", "midnight", "sunrise", "0", "6000", "12000", "18000");

    private static final int DAY_TICKS = 24000;
    private static final int TIME_STEP = 1000;

    private final LavaRaiseManager manager;

    public LavaRaiseCommand(LavaRaiseManager manager) {
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
            case "on" -> {
                if (manager.isEnabled()) {
                    Messages.send(sender, "lavaraise.already-enabled");
                } else {
                    manager.enable();
                    Messages.send(sender, "lavaraise.enabled",
                            "time", LavaRaiseManager.formatTime(manager.startTime()));
                }
            }
            case "off" -> {
                if (!manager.isEnabled()) {
                    Messages.send(sender, "lavaraise.not-enabled");
                } else {
                    manager.disable();
                    Messages.send(sender, manager.isActive()
                            ? "lavaraise.disabled-draining" : "lavaraise.disabled");
                }
            }
            case "status" -> sendStatus(sender);
            case "cancel" -> {
                if (manager.isActive()) {
                    manager.cancelEvent();
                    Messages.send(sender, "lavaraise.draining-now");
                } else {
                    Messages.send(sender, "lavaraise.no-event");
                }
            }
            case "purge" -> {
                Integer y = parseInt(sender, args, -63, 319);
                if (y == null) {
                    return true;
                }
                if (manager.startPurge(y)) {
                    Messages.send(sender, "lavaraise.purge-start", "y", String.valueOf(y));
                } else {
                    Messages.send(sender, "lavaraise.purge-busy");
                }
            }
            case "start", "end" -> {
                if (args.length < 2) {
                    return false;
                }
                int ticks = LavaRaiseManager.parseTime(args[1]);
                if (ticks < 0) {
                    Messages.send(sender, "lavaraise.invalid-time", "input", args[1]);
                    return true;
                }
                boolean isStart = args[0].equalsIgnoreCase("start");
                manager.set(isStart ? "start-time" : "end-time", ticks);
                Messages.send(sender, isStart ? "lavaraise.start-set" : "lavaraise.end-set",
                        "time", LavaRaiseManager.formatTime(ticks));
            }
            case "duration" -> {
                Integer seconds = parseInt(sender, args, 10, 7200);
                if (seconds != null) {
                    manager.set("rise-duration-seconds", seconds);
                    Messages.send(sender, "lavaraise.duration-set", "seconds", String.valueOf(seconds));
                }
            }
            case "maxy" -> {
                Integer y = parseInt(sender, args, -63, 319);
                if (y != null) {
                    manager.set("max-y", y);
                    Messages.send(sender, "lavaraise.maxy-set", "y", String.valueOf(y));
                }
            }
            case "water", "blocks", "mobs" -> {
                if (args.length < 2 || !(args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
                    return false;
                }
                boolean on = args[1].equalsIgnoreCase("on");
                String state = on ? "on" : "off";
                switch (args[0].toLowerCase()) {
                    case "water" -> {
                        manager.set("replace-water", on);
                        Messages.send(sender, "lavaraise.water-set", "state", state);
                    }
                    case "blocks" -> {
                        manager.set("burn-placed-blocks", on);
                        Messages.send(sender, "lavaraise.blocks-set", "state", state);
                    }
                    default -> {
                        manager.set("damage-mobs", on);
                        Messages.send(sender, "lavaraise.mobs-set", "state", state);
                    }
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
        Menu.open(player, "Lava Raise", menu -> {
            menu.header(MenuItem.toggle("Lava Raise", Material.LAVA_BUCKET,
                    List.of("A client-side lava tide rises daily,",
                            "then drains. Phase: " + phaseLabel()),
                    manager::isEnabled,
                    on -> {
                        if (on) {
                            manager.enable();
                        } else {
                            manager.disable();
                        }
                    }));

            if (manager.isActive()) {
                menu.add(MenuItem.button("Drain now", NamedTextColor.GOLD, Material.BUCKET,
                        List.of("Drain the lava immediately."),
                        clicker -> manager.cancelEvent()));
            }

            menu.add(MenuItem.stepper("Rises at", Material.CLOCK,
                    List.of("World time the lava starts rising."),
                    () -> LavaRaiseManager.formatTime(manager.startTime()),
                    forward -> manager.set("start-time", stepTime(manager.startTime(), forward))));

            menu.add(MenuItem.stepper("Drains at", Material.CLOCK,
                    List.of("World time the lava drains away."),
                    () -> LavaRaiseManager.formatTime(manager.endTime()),
                    forward -> manager.set("end-time", stepTime(manager.endTime(), forward))));

            menu.add(MenuItem.stepper("Travel time", Material.MINECART,
                    List.of("Seconds to climb from floor to max."),
                    () -> manager.riseDurationSeconds() + "s",
                    forward -> {
                        int seconds = manager.riseDurationSeconds();
                        manager.set("rise-duration-seconds",
                                forward ? Math.min(7200, seconds + 60) : Math.max(10, seconds - 60));
                    }));

            menu.add(MenuItem.stepper("Max level", Material.LADDER,
                    List.of("Highest Y the lava reaches."),
                    () -> "Y=" + manager.maxY(),
                    forward -> {
                        int y = manager.maxY();
                        manager.set("max-y", forward ? Math.min(319, y + 8) : Math.max(-63, y - 8));
                    }));

            menu.add(MenuItem.toggle("Replace water", Material.WATER_BUCKET,
                    List.of("Oceans fill with lava; water stops protecting."),
                    manager::replaceWater, on -> manager.set("replace-water", on)));

            menu.add(MenuItem.toggle("Burn builds", Material.FLINT_AND_STEEL,
                    List.of("Player-placed burnable blocks burn away."),
                    manager::burnPlacedBlocks, on -> manager.set("burn-placed-blocks", on)));

            menu.add(MenuItem.toggle("Damage mobs", Material.ROTTEN_FLESH,
                    List.of("Mobs in the lava burn and take damage."),
                    manager::damageMobs, on -> manager.set("damage-mobs", on)));
        });
    }

    private String phaseLabel() {
        return switch (manager.phase()) {
            case IDLE -> "idle";
            case ARMED -> "armed";
            case RISING -> "rising (Y=" + manager.currentLavaY() + ")";
            case HOLDING -> "holding (Y=" + manager.currentLavaY() + ")";
            case DRAINING -> "draining (Y=" + manager.currentLavaY() + ")";
        };
    }

    private static int stepTime(int ticks, boolean forward) {
        return Math.floorMod(ticks + (forward ? TIME_STEP : -TIME_STEP), DAY_TICKS);
    }

    private Integer parseInt(CommandSender sender, String[] args, int min, int max) {
        if (args.length < 2) {
            Messages.send(sender, "general.missing-argument", "name", "value");
            return null;
        }
        try {
            int value = Integer.parseInt(args[1]);
            if (value < min || value > max) {
                Messages.send(sender, "general.value-range",
                        "min", String.valueOf(min), "max", String.valueOf(max));
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            Messages.send(sender, "general.not-a-number", "input", args[1]);
            return null;
        }
    }

    private void sendStatus(CommandSender sender) {
        String phase = switch (manager.phase()) {
            case IDLE -> "disabled";
            case ARMED -> "armed (waiting for start time)";
            case RISING -> "RISING: lava at Y=" + manager.currentLavaY();
            case HOLDING -> "HOLDING at Y=" + manager.currentLavaY();
            case DRAINING -> "DRAINING: lava at Y=" + manager.currentLavaY();
        };
        Messages.send(sender, "lavaraise.status-state", "state", phase);
        Messages.send(sender, "lavaraise.status-settings",
                "start", LavaRaiseManager.formatTime(manager.startTime()),
                "end", LavaRaiseManager.formatTime(manager.endTime()),
                "seconds", String.valueOf(manager.riseDurationSeconds()),
                "y", String.valueOf(manager.maxY()),
                "water", manager.replaceWater() ? "replaced" : "kept");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("start") || args[0].equalsIgnoreCase("end"))) {
            return TIME_SUGGESTIONS.stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("water")
                || args[0].equalsIgnoreCase("blocks") || args[0].equalsIgnoreCase("mobs"))) {
            return List.of("on", "off").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
