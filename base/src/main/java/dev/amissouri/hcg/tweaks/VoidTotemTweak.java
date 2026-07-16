package dev.amissouri.hcg.tweaks;

import java.util.List;

import dev.amissouri.hcg.Messages;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class VoidTotemTweak implements Tweak {

    private static final String PATH = "tweaks.voidtotem.";
    private static final int[] LEVEL_STEPS = {1, 2, 3, 5, 7, 10};

    private final JavaPlugin plugin;

    private volatile boolean enabled;
    private volatile int level;
    private volatile boolean consume;
    private volatile boolean effect;
    private volatile int maxSeconds;

    public VoidTotemTweak(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();
        enabled = config.getBoolean(PATH + "enabled", false);
        level = Math.clamp(config.getInt(PATH + "level", 5), 1, 10);
        consume = config.getBoolean(PATH + "consume", true);
        effect = config.getBoolean(PATH + "effect", true);
        maxSeconds = Math.clamp(config.getInt(PATH + "max-seconds", 30), 5, 120);
    }

    @Override
    public String id() {
        return "voidtotem";
    }

    @Override
    public String displayName() {
        return Messages.raw("tweaks.voidtotem.name");
    }

    @Override
    public Material icon() {
        return Material.TOTEM_OF_UNDYING;
    }

    @Override
    public List<String> summary() {
        return List.of(Messages.raw("tweaks.voidtotem.summary"));
    }

    @Override
    public String command() {
        return "/voidtotem";
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
                new Setting.Of("Levitation Level", Material.SHULKER_SHELL,
                        () -> String.valueOf(level),
                        forward -> setLevel(Setting.step(level, LEVEL_STEPS, forward)),
                        List.of("How strongly the totem floats you",
                                "up out of the void. Higher rises",
                                "faster.")),
                new Setting.Of("Consume Totem", Material.TOTEM_OF_UNDYING,
                        () -> onOff(consume),
                        forward -> setConsume(!consume),
                        List.of("Use up the totem when it saves you.",
                                "OFF makes a held totem unlimited",
                                "void protection.")),
                new Setting.Of("Totem Effect", Material.FIREWORK_ROCKET,
                        () -> onOff(effect),
                        forward -> setEffect(!effect),
                        List.of("Play the totem's pop animation and",
                                "sound when it saves you.")));
    }

    public int level() {
        return level;
    }

    public void setLevel(int value) {
        level = Math.clamp(value, 1, 10);
        write("level", level);
    }

    public boolean consume() {
        return consume;
    }

    public void setConsume(boolean value) {
        consume = value;
        write("consume", value);
    }

    public boolean effect() {
        return effect;
    }

    public void setEffect(boolean value) {
        effect = value;
        write("effect", value);
    }

    public int maxSeconds() {
        return maxSeconds;
    }

    private void write(String key, Object value) {
        plugin.getConfig().set(PATH + key, value);
        plugin.saveConfig();
    }

    private static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }
}
