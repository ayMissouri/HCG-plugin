package dev.amissouri.hcg.tweaks;

import java.util.List;

import dev.amissouri.hcg.Messages;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class PathSprintTweak implements Tweak {

    private static final String PATH = "tweaks.pathsprint.";
    private static final int[] SPEED_STEPS = {1, 2, 3};

    private final JavaPlugin plugin;

    private volatile boolean enabled;
    private volatile int speed;
    private volatile boolean requireSprint;
    private volatile boolean particles;

    public PathSprintTweak(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();
        enabled = config.getBoolean(PATH + "enabled", false);
        speed = Math.clamp(config.getInt(PATH + "speed", 1), 1, 3);
        requireSprint = config.getBoolean(PATH + "require-sprint", true);
        particles = config.getBoolean(PATH + "particles", false);
    }

    @Override
    public String id() {
        return "pathsprint";
    }

    @Override
    public String displayName() {
        return Messages.raw("tweaks.pathsprint.name");
    }

    @Override
    public Material icon() {
        return Material.DIRT_PATH;
    }

    @Override
    public List<String> summary() {
        return List.of(Messages.raw("tweaks.pathsprint.summary"));
    }

    @Override
    public String command() {
        return "/pathsprint";
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
                new Setting.Of("Speed Boost", Material.SUGAR,
                        () -> String.valueOf(speed),
                        forward -> setSpeed(Setting.step(speed, SPEED_STEPS, forward)),
                        List.of("How much faster you run on a path.",
                                "1 is a gentle nudge, 3 is a real",
                                "sprint lane.")),
                new Setting.Of("Require Sprint", Material.LEATHER_BOOTS,
                        () -> onOff(requireSprint),
                        forward -> setRequireSprint(!requireSprint),
                        List.of("Only boost while you're sprinting.",
                                "OFF speeds up plain walking on a",
                                "path too.")),
                new Setting.Of("Particles", Material.FIREWORK_STAR,
                        () -> onOff(particles),
                        forward -> setParticles(!particles),
                        List.of("Show the speed particle trail while",
                                "the boost is active.")));
    }

    public int speed() {
        return speed;
    }

    public void setSpeed(int value) {
        speed = Math.clamp(value, 1, 3);
        write("speed", speed);
    }

    public boolean requireSprint() {
        return requireSprint;
    }

    public void setRequireSprint(boolean value) {
        requireSprint = value;
        write("require-sprint", value);
    }

    public boolean particles() {
        return particles;
    }

    public void setParticles(boolean value) {
        particles = value;
        write("particles", value);
    }

    private void write(String key, Object value) {
        plugin.getConfig().set(PATH + key, value);
        plugin.saveConfig();
    }

    private static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }
}
