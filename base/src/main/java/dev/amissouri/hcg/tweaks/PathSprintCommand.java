package dev.amissouri.hcg.tweaks;

import java.util.List;
import java.util.Locale;

import dev.amissouri.hcg.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class PathSprintCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("on", "off", "speed", "sprint",
            "particles", "gui", "reload");
    private static final List<String> STATES = List.of("on", "off");

    private final PathSprintTweak tweak;
    private final TweaksGui gui;

    public PathSprintCommand(PathSprintTweak tweak, TweaksGui gui) {
        this.tweak = tweak;
        this.gui = gui;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            openOrStatus(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "menu", "gui" -> openOrStatus(sender);
            case "on" -> setEnabled(sender, true);
            case "off" -> setEnabled(sender, false);
            case "reload" -> {
                tweak.reload();
                Messages.send(sender, "tweaks.pathsprint.reloaded");
            }
            case "speed" -> {
                Integer value = number(sender, args, 1, 3);
                if (value != null) {
                    tweak.setSpeed(value);
                    report(sender, "Speed boost", String.valueOf(tweak.speed()));
                }
            }
            case "sprint" -> {
                Boolean value = state(sender, args);
                if (value != null) {
                    tweak.setRequireSprint(value);
                    report(sender, "Require sprint", onOff(value));
                }
            }
            case "particles" -> {
                Boolean value = state(sender, args);
                if (value != null) {
                    tweak.setParticles(value);
                    report(sender, "Particles", onOff(value));
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
            openGui(player);
        } else {
            sendStatus(sender);
        }
    }

    private void openGui(Player player) {
        if (!player.hasPermission("hcg.tweaks")) {
            Messages.send(player, "general.no-permission");
            return;
        }
        gui.openTweak(player, tweak);
    }

    private void setEnabled(CommandSender sender, boolean on) {
        if (tweak.isEnabled() == on) {
            Messages.send(sender, "tweaks.already",
                    "tweak", tweak.displayName(), "state", on ? "enabled" : "disabled");
            return;
        }
        tweak.setEnabled(on);
        Messages.broadcastOps("tweaks.set",
                "tweak", tweak.displayName(), "state", on ? "enabled" : "disabled");
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(Component.text("--- ", NamedTextColor.DARK_GRAY)
                .append(Component.text(tweak.displayName(), NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text(" ---", NamedTextColor.DARK_GRAY)));
        sender.sendMessage(Component.text("Status: ", NamedTextColor.GRAY)
                .append(tweak.isEnabled()
                        ? Component.text("ENABLED", NamedTextColor.GREEN, TextDecoration.BOLD)
                        : Component.text("DISABLED", NamedTextColor.RED, TextDecoration.BOLD)));
        line(sender, "Speed boost", String.valueOf(tweak.speed()));
        line(sender, "Require sprint", onOff(tweak.requireSprint()));
        line(sender, "Particles", onOff(tweak.particles()));
        sender.sendMessage(Component.text("Change with /pathsprint <setting> <value>, "
                + "or open the chest menu in-game with /pathsprint.", NamedTextColor.DARK_GRAY)
                .decorate(TextDecoration.ITALIC));
    }

    private void line(CommandSender sender, String name, String value) {
        sender.sendMessage(Component.text(name + ": ", NamedTextColor.GRAY)
                .append(Component.text(value, NamedTextColor.YELLOW)));
    }

    private void report(CommandSender sender, String name, String value) {
        Messages.send(sender, "tweaks.pathsprint.setting-set", "setting", name, "value", value);
    }

    private Boolean state(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Messages.send(sender, "general.missing-argument", "name", "on|off");
            return null;
        }
        String value = args[1].toLowerCase(Locale.ROOT);
        if (!STATES.contains(value)) {
            Messages.send(sender, "tweaks.pathsprint.bad-value", "input", args[1], "options", "on, off");
            return null;
        }
        return value.equals("on");
    }

    private Integer number(CommandSender sender, String[] args, int min, int max) {
        if (args.length < 2) {
            Messages.send(sender, "general.missing-argument", "name", "number");
            return null;
        }
        int value;
        try {
            value = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            Messages.send(sender, "general.not-a-number", "input", args[1]);
            return null;
        }
        if (value < min || value > max) {
            Messages.send(sender, "general.value-range",
                    "min", String.valueOf(min), "max", String.valueOf(max));
            return null;
        }
        return value;
    }

    private static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream()
                    .filter(sub -> sub.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2) {
            List<String> options = switch (args[0].toLowerCase(Locale.ROOT)) {
                case "sprint", "particles" -> STATES;
                case "speed" -> List.of("1", "2", "3");
                default -> List.of();
            };
            return options.stream()
                    .filter(option -> option.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }
}
