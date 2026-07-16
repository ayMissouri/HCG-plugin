package dev.amissouri.hcg.tweaks;

import java.util.List;
import java.util.Locale;

import dev.amissouri.hcg.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.java.JavaPlugin;

public final class MobHealthTweak implements Tweak {

    public enum Display {
        ABOVE_MOB,
        ACTION_BAR
    }

    public enum Style {
        HEARTS,
        NUMBERS,
        BOTH
    }

    private static final String PATH = "tweaks.mobhealth.";
    private static final int[] RANGE_STEPS = {8, 12, 16, 24, 32};
    private static final int HEART_COUNT = 10;
    private static final String HEART = "❤";

    private static final Attribute MAX_HEALTH = findMaxHealth();

    private final JavaPlugin plugin;

    private volatile boolean enabled;
    private volatile Display display;
    private volatile Style style;
    private volatile int range;
    private volatile boolean includePlayers;

    public MobHealthTweak(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();
        enabled = config.getBoolean(PATH + "enabled", false);
        display = parse(config.getString(PATH + "display"), Display.class, Display.ABOVE_MOB);
        style = parse(config.getString(PATH + "style"), Style.class, Style.BOTH);
        range = Math.clamp(config.getInt(PATH + "range", 12), 1, 32);
        includePlayers = config.getBoolean(PATH + "include-players", false);
    }

    @Override
    public String id() {
        return "mobhealth";
    }

    @Override
    public String displayName() {
        return Messages.raw("tweaks.mobhealth.name");
    }

    @Override
    public Material icon() {
        return Material.GOLDEN_APPLE;
    }

    @Override
    public List<String> summary() {
        return List.of(Messages.raw("tweaks.mobhealth.summary"));
    }

    @Override
    public String command() {
        return "/mobhealth";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean value) {
        enabled = value;
        write("enabled", value);
    }

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting.Of("Display", Material.ARMOR_STAND,
                        () -> display.name().replace('_', ' '),
                        forward -> setDisplay(Setting.step(display, Display.values(), forward)),
                        List.of("ABOVE MOB - a floating health bar",
                                "over the mob, seen only by whoever",
                                "is looking at it",
                                "ACTION BAR - text above the",
                                "looking player's hotbar")),
                new Setting.Of("Style", Material.PAINTING,
                        () -> style.name(),
                        forward -> setStyle(Setting.step(style, Style.values(), forward)),
                        List.of("HEARTS - ten hearts that empty",
                                "as the mob loses health",
                                "NUMBERS - 12.5/20",
                                "BOTH - hearts and numbers")),
                new Setting.Of("Range", Material.SPYGLASS,
                        () -> String.valueOf(range),
                        forward -> setRange(Setting.step(range, RANGE_STEPS, forward)),
                        List.of("How far away (blocks) a looked-at",
                                "mob still shows its health.")),
                new Setting.Of("Show Players", Material.PLAYER_HEAD,
                        () -> onOff(includePlayers),
                        forward -> setIncludePlayers(!includePlayers),
                        List.of("Also show other players' health.")));
    }

    public Display display() {
        return display;
    }

    public void setDisplay(Display value) {
        display = value;
        write("display", value.name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    public Style style() {
        return style;
    }

    public void setStyle(Style value) {
        style = value;
        write("style", value.name().toLowerCase(Locale.ROOT));
    }

    public int range() {
        return range;
    }

    public void setRange(int value) {
        range = Math.clamp(value, 1, 32);
        write("range", range);
    }

    public boolean includePlayers() {
        return includePlayers;
    }

    public void setIncludePlayers(boolean value) {
        includePlayers = value;
        write("include-players", value);
    }

    public Component barText(LivingEntity mob) {
        return health(mob);
    }

    public Component actionBarText(LivingEntity mob) {
        return mob.name().color(NamedTextColor.WHITE)
                .append(Component.space())
                .append(health(mob));
    }

    private Component health(LivingEntity mob) {
        double max = Math.max(maxHealth(mob), 0.1);
        double current = Math.clamp(mob.getHealth(), 0.0, max);
        double fraction = current / max;
        NamedTextColor numberColor = fraction > 0.5 ? NamedTextColor.GREEN
                : fraction > 0.25 ? NamedTextColor.YELLOW : NamedTextColor.RED;
        Component numbers = Component.text(compact(current) + "/" + compact(max), numberColor);
        if (style == Style.NUMBERS) {
            return numbers;
        }
        int filled = current <= 0.0 ? 0
                : Math.clamp((int) Math.ceil(fraction * HEART_COUNT), 1, HEART_COUNT);
        Component hearts = Component.text(HEART.repeat(filled), NamedTextColor.RED)
                .append(Component.text(HEART.repeat(HEART_COUNT - filled), NamedTextColor.DARK_GRAY));
        if (style == Style.HEARTS) {
            return hearts;
        }
        return hearts.append(Component.space()).append(numbers);
    }

    private static double maxHealth(LivingEntity mob) {
        AttributeInstance instance = MAX_HEALTH == null ? null : mob.getAttribute(MAX_HEALTH);
        return instance != null ? instance.getValue() : Math.max(mob.getHealth(), 20.0);
    }

    private static Attribute findMaxHealth() {
        for (String key : new String[] {"max_health", "generic.max_health"}) {
            Attribute attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key));
            if (attribute != null) {
                return attribute;
            }
        }
        return null;
    }

    private static String compact(double value) {
        double rounded = Math.round(value * 10.0) / 10.0;
        long whole = (long) rounded;
        return rounded == whole ? Long.toString(whole) : Double.toString(rounded);
    }

    private void write(String key, Object value) {
        plugin.getConfig().set(PATH + key, value);
        plugin.saveConfig();
    }

    private static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }

    private <E extends Enum<E>> E parse(String raw, Class<E> type, E fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Unknown mobhealth " + type.getSimpleName().toLowerCase(Locale.ROOT)
                    + " '" + raw + "' in config.yml, using " + fallback.name().toLowerCase(Locale.ROOT) + ".");
            return fallback;
        }
    }
}
