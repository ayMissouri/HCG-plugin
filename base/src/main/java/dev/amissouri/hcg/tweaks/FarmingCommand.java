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

public final class FarmingCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("on", "off", "harvest", "replant",
            "xp", "xpamount", "hoe", "durability", "radius", "sound", "twerk", "twerkradius",
            "twerkchance", "gui", "reload");
    private static final List<String> STATES = List.of("on", "off");

    private final FarmingTweak tweak;
    private final TweaksGui gui;

    public FarmingCommand(FarmingTweak tweak, TweaksGui gui) {
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
                Messages.send(sender, "tweaks.farming.reloaded");
            }
            case "harvest" -> {
                Boolean value = state(sender, args);
                if (value != null) {
                    tweak.setRightClickHarvest(value);
                    report(sender, "Right-click harvest", onOff(value));
                }
            }
            case "replant" -> {
                Boolean value = state(sender, args);
                if (value != null) {
                    tweak.setReplantOnBreak(value);
                    report(sender, "Replant on break", onOff(value));
                }
            }
            case "xp" -> {
                Boolean value = state(sender, args);
                if (value != null) {
                    tweak.setXp(value);
                    report(sender, "Crops give XP", onOff(value));
                }
            }
            case "xpamount" -> {
                Integer value = number(sender, args, 0, 100);
                if (value != null) {
                    tweak.setXpAmount(value);
                    report(sender, "XP per crop", String.valueOf(tweak.xpAmount()));
                }
            }
            case "hoe" -> {
                Boolean value = state(sender, args);
                if (value != null) {
                    tweak.setRequireHoe(value);
                    report(sender, "Require hoe", onOff(value));
                }
            }
            case "durability" -> {
                Boolean value = state(sender, args);
                if (value != null) {
                    tweak.setHoeDurability(value);
                    report(sender, "Hoe durability", onOff(value));
                }
            }
            case "radius" -> {
                Integer value = number(sender, args, 0, 8);
                if (value != null) {
                    tweak.setRadius(value);
                    report(sender, "Harvest radius", String.valueOf(tweak.radius()));
                }
            }
            case "sound" -> {
                Boolean value = state(sender, args);
                if (value != null) {
                    tweak.setSound(value);
                    report(sender, "Sound", onOff(value));
                }
            }
            case "twerk" -> {
                Boolean value = state(sender, args);
                if (value != null) {
                    tweak.setTwerkGrow(value);
                    report(sender, "TwerkGrow", onOff(value));
                }
            }
            case "twerkradius" -> {
                Integer value = number(sender, args, 1, 8);
                if (value != null) {
                    tweak.setTwerkRadius(value);
                    report(sender, "Twerk radius", String.valueOf(tweak.twerkRadius()));
                }
            }
            case "twerkchance" -> {
                Integer value = number(sender, args, 0, 100);
                if (value != null) {
                    tweak.setTwerkChance(value);
                    report(sender, "Twerk chance", tweak.twerkChance() + "%");
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
        line(sender, "Right-click harvest", onOff(tweak.rightClickHarvest()));
        line(sender, "Replant on break", onOff(tweak.replantOnBreak()));
        line(sender, "Crops give XP", onOff(tweak.xp()));
        line(sender, "XP per crop", String.valueOf(tweak.xpAmount()));
        line(sender, "Require hoe", onOff(tweak.requireHoe()));
        line(sender, "Hoe durability", onOff(tweak.hoeDurability()));
        line(sender, "Harvest radius", String.valueOf(tweak.radius()));
        line(sender, "Sound", onOff(tweak.sound()));
        line(sender, "TwerkGrow", onOff(tweak.twerkGrow()));
        line(sender, "Twerk radius", String.valueOf(tweak.twerkRadius()));
        line(sender, "Twerk chance", tweak.twerkChance() + "%");
        sender.sendMessage(Component.text("Change with /farming <setting> <value>, "
                + "or open the chest menu in-game with /farming.", NamedTextColor.DARK_GRAY)
                .decorate(TextDecoration.ITALIC));
    }

    private void line(CommandSender sender, String name, String value) {
        sender.sendMessage(Component.text(name + ": ", NamedTextColor.GRAY)
                .append(Component.text(value, NamedTextColor.YELLOW)));
    }

    private void report(CommandSender sender, String name, String value) {
        Messages.send(sender, "tweaks.farming.setting-set", "setting", name, "value", value);
    }

    private Boolean state(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Messages.send(sender, "general.missing-argument", "name", "on|off");
            return null;
        }
        String value = args[1].toLowerCase(Locale.ROOT);
        if (!STATES.contains(value)) {
            Messages.send(sender, "tweaks.farming.bad-value", "input", args[1], "options", "on, off");
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
                case "harvest", "replant", "xp", "hoe", "durability", "sound", "twerk" -> STATES;
                case "radius" -> List.of("0", "1", "2", "3");
                case "xpamount" -> List.of("1", "2", "3", "5", "7", "10");
                case "twerkradius" -> List.of("2", "3", "4", "6", "8");
                case "twerkchance" -> List.of("10", "20", "30", "50");
                default -> List.of();
            };
            return options.stream()
                    .filter(option -> option.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }
}
