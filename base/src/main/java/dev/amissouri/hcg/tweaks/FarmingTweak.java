package dev.amissouri.hcg.tweaks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import dev.amissouri.hcg.Messages;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class FarmingTweak implements Tweak {

    private static final String PATH = "tweaks.farming.";
    private static final int[] XP_STEPS = {1, 2, 3, 5, 7, 10};
    private static final int[] RADIUS_STEPS = {0, 1, 2, 3};
    private static final int[] TWERK_RADIUS_STEPS = {2, 3, 4, 6, 8};
    private static final int MAX_RADIUS = 8;

    private static final Map<Material, Material> CROPS = crops();

    private final JavaPlugin plugin;
    private final Map<UUID, Long> lastTwerk = new ConcurrentHashMap<>();

    private volatile boolean enabled;
    private volatile boolean rightClickHarvest;
    private volatile boolean replantOnBreak;
    private volatile boolean xp;
    private volatile int xpAmount;
    private volatile boolean requireHoe;
    private volatile boolean hoeDurability;
    private volatile int radius;
    private volatile boolean sound;
    private volatile boolean twerkGrow;
    private volatile int twerkRadius;
    private volatile int twerkChance;
    private volatile int twerkCooldownMs;

    public FarmingTweak(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();
        enabled = config.getBoolean(PATH + "enabled", false);
        rightClickHarvest = config.getBoolean(PATH + "right-click-harvest", true);
        replantOnBreak = config.getBoolean(PATH + "replant-on-break", true);
        xp = config.getBoolean(PATH + "xp", true);
        xpAmount = Math.clamp(config.getInt(PATH + "xp-amount", 2), 0, 100);
        requireHoe = config.getBoolean(PATH + "require-hoe", false);
        hoeDurability = config.getBoolean(PATH + "hoe-durability", false);
        radius = Math.clamp(config.getInt(PATH + "radius", 0), 0, MAX_RADIUS);
        sound = config.getBoolean(PATH + "sound", true);
        twerkGrow = config.getBoolean(PATH + "twerk-grow.enabled", false);
        twerkRadius = Math.clamp(config.getInt(PATH + "twerk-grow.radius", 4), 1, MAX_RADIUS);
        twerkChance = Math.clamp(config.getInt(PATH + "twerk-grow.chance", 20), 0, 100);
        twerkCooldownMs = Math.clamp(config.getInt(PATH + "twerk-grow.cooldown-ms", 250), 0, 60000);
    }

    @Override
    public String id() {
        return "farming";
    }

    @Override
    public String displayName() {
        return Messages.raw("tweaks.farming.name");
    }

    @Override
    public Material icon() {
        return Material.WHEAT;
    }

    @Override
    public List<String> summary() {
        return List.of(Messages.raw("tweaks.farming.summary"));
    }

    @Override
    public String command() {
        return "/farming";
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
                new Setting.Of("Right-Click Harvest", Material.IRON_HOE,
                        () -> onOff(rightClickHarvest),
                        forward -> setRightClickHarvest(!rightClickHarvest),
                        List.of("Right-click a grown crop to harvest",
                                "it and reset it to a seedling,",
                                "without trampling the farmland.")),
                new Setting.Of("Replant On Break", Material.WHEAT_SEEDS,
                        () -> onOff(replantOnBreak),
                        forward -> setReplantOnBreak(!replantOnBreak),
                        List.of("When a grown crop is broken, replant",
                                "it from its own drops so the field",
                                "refills itself.")),
                new Setting.Of("Crops Give XP", Material.EXPERIENCE_BOTTLE,
                        () -> onOff(xp),
                        forward -> setXp(!xp),
                        List.of("Harvesting a grown crop grants",
                                "experience, like mining an ore.")),
                new Setting.Of("XP Per Crop", Material.LAPIS_LAZULI,
                        () -> String.valueOf(xpAmount),
                        forward -> setXpAmount(Setting.step(xpAmount, XP_STEPS, forward)),
                        List.of("How much experience each",
                                "harvested crop grants.",
                                "Unused when Crops Give XP is off.")),
                new Setting.Of("Require Hoe", Material.WOODEN_HOE,
                        () -> onOff(requireHoe),
                        forward -> setRequireHoe(!requireHoe),
                        List.of("Only harvest on right-click while",
                                "holding a hoe. Off means any item",
                                "or an empty hand works.")),
                new Setting.Of("Hoe Durability", Material.ANVIL,
                        () -> onOff(hoeDurability),
                        forward -> setHoeDurability(!hoeDurability),
                        List.of("A right-click harvest costs a held",
                                "hoe one durability point per crop.")),
                new Setting.Of("Harvest Radius", Material.FARMLAND,
                        () -> radius == 0 ? "OFF" : String.valueOf(radius),
                        forward -> setRadius(Setting.step(radius, RADIUS_STEPS, forward)),
                        List.of("Right-click harvests every grown",
                                "crop within this many blocks, to",
                                "clear a field at once. 0 harvests",
                                "only the crop clicked.")),
                new Setting.Of("Sound", Material.NOTE_BLOCK,
                        () -> onOff(sound),
                        forward -> setSound(!sound),
                        List.of("Play a soft sound when a",
                                "right-click harvest lands.")),
                new Setting.Of("TwerkGrow", Material.BONE_MEAL,
                        () -> onOff(twerkGrow),
                        forward -> setTwerkGrow(!twerkGrow),
                        List.of("Spam sneak near your crops and they",
                                "grow a little, every now and then.",
                                "A nod to the SkyFactory bit.")),
                new Setting.Of("Twerk Radius", Material.MOSS_BLOCK,
                        () -> String.valueOf(twerkRadius),
                        forward -> setTwerkRadius(Setting.step(twerkRadius, TWERK_RADIUS_STEPS, forward)),
                        List.of("How far around you a sneak-spam",
                                "reaches to nudge crops along.",
                                "Unused when TwerkGrow is off.")));
    }

    public boolean rightClickHarvest() {
        return rightClickHarvest;
    }

    public void setRightClickHarvest(boolean value) {
        rightClickHarvest = value;
        write("right-click-harvest", value);
    }

    public boolean replantOnBreak() {
        return replantOnBreak;
    }

    public void setReplantOnBreak(boolean value) {
        replantOnBreak = value;
        write("replant-on-break", value);
    }

    public boolean xp() {
        return xp;
    }

    public void setXp(boolean value) {
        xp = value;
        write("xp", value);
    }

    public int xpAmount() {
        return xpAmount;
    }

    public void setXpAmount(int value) {
        xpAmount = Math.clamp(value, 0, 100);
        write("xp-amount", xpAmount);
    }

    public boolean requireHoe() {
        return requireHoe;
    }

    public void setRequireHoe(boolean value) {
        requireHoe = value;
        write("require-hoe", value);
    }

    public boolean hoeDurability() {
        return hoeDurability;
    }

    public void setHoeDurability(boolean value) {
        hoeDurability = value;
        write("hoe-durability", value);
    }

    public int radius() {
        return radius;
    }

    public void setRadius(int value) {
        radius = Math.clamp(value, 0, MAX_RADIUS);
        write("radius", radius);
    }

    public boolean sound() {
        return sound;
    }

    public void setSound(boolean value) {
        sound = value;
        write("sound", value);
    }

    public boolean twerkGrow() {
        return twerkGrow;
    }

    public void setTwerkGrow(boolean value) {
        twerkGrow = value;
        write("twerk-grow.enabled", value);
    }

    public int twerkRadius() {
        return twerkRadius;
    }

    public void setTwerkRadius(int value) {
        twerkRadius = Math.clamp(value, 1, MAX_RADIUS);
        write("twerk-grow.radius", twerkRadius);
    }

    public int twerkChance() {
        return twerkChance;
    }

    public void setTwerkChance(int value) {
        twerkChance = Math.clamp(value, 0, 100);
        write("twerk-grow.chance", twerkChance);
    }

    boolean canRightClickHarvest(Player player, Block block, ItemStack tool) {
        if (!enabled || !rightClickHarvest || !isHarvestable(block)
                || !player.hasPermission("hcg.farming.use") || !survivalLike(player)) {
            return false;
        }
        return !requireHoe || isHoe(tool);
    }

    boolean canHarvestOnBreak(Player player, Block block) {
        return enabled && isHarvestable(block)
                && player.hasPermission("hcg.farming.use") && survivalLike(player);
    }

    int harvest(Player player, Block origin, ItemStack toolIn, boolean useRadius) {
        List<Block> targets = new ArrayList<>();
        targets.add(origin);
        int reach = useRadius ? radius : 0;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                Block block = origin.getRelative(dx, 0, dz);
                if (isHarvestable(block)) {
                    targets.add(block);
                }
            }
        }

        boolean survival = survivalLike(player);
        ItemStack tool = toolIn;
        int harvested = 0;
        int gainedXp = 0;
        for (Block block : targets) {
            Collection<ItemStack> drops = block.getDrops(tool);
            removeOneSeed(drops, CROPS.get(block.getType()));
            resetAge(block);
            Location dropLocation = block.getLocation().add(0.5, 0.25, 0.5);
            for (ItemStack drop : drops) {
                if (drop != null && !drop.getType().isAir() && drop.getAmount() > 0) {
                    block.getWorld().dropItemNaturally(dropLocation, drop);
                }
            }
            harvested++;
            if (xp) {
                gainedXp += xpAmount;
            }
            if (survival && hoeDurability && isHoe(tool) && isDamageable(tool)) {
                tool = damage(player, tool);
                if (tool.getType().isAir()) {
                    break;
                }
            }
        }

        if (harvested > 0) {
            if (gainedXp > 0) {
                spawnXp(origin, gainedXp);
            }
            if (sound) {
                origin.getWorld().playSound(origin.getLocation(),
                        Sound.BLOCK_SWEET_BERRY_BUSH_PICK_BERRIES, 0.7f, 1.1f);
            }
        }
        return harvested;
    }

    void giveCropXp(Block block) {
        if (xp && xpAmount > 0) {
            spawnXp(block, xpAmount);
        }
    }

    void twerk(Player player) {
        if (!enabled || !twerkGrow || !survivalLike(player)
                || !player.hasPermission("hcg.farming.use")) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastTwerk.get(player.getUniqueId());
        if (last != null && now - last < twerkCooldownMs) {
            return;
        }
        lastTwerk.put(player.getUniqueId(), now);

        Block center = player.getLocation().getBlock();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int dx = -twerkRadius; dx <= twerkRadius; dx++) {
            for (int dz = -twerkRadius; dz <= twerkRadius; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    Block block = center.getRelative(dx, dy, dz);
                    if (!CROPS.containsKey(block.getType())
                            || !Bukkit.isOwnedByCurrentRegion(block)
                            || !block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
                        continue;
                    }
                    if (random.nextInt(100) < twerkChance && growOne(block)) {
                        block.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                                block.getLocation().add(0.5, 0.5, 0.5), 3, 0.3, 0.3, 0.3, 0.0);
                    }
                }
            }
        }
    }

    void forget(UUID id) {
        lastTwerk.remove(id);
    }

    private static boolean growOne(Block block) {
        BlockData data = block.getBlockData();
        if (!(data instanceof Ageable crop) || crop.getAge() >= crop.getMaximumAge()) {
            return false;
        }
        crop.setAge(crop.getAge() + 1);
        BlockState state = block.getState();
        state.setBlockData(crop);
        if (!new BlockGrowEvent(block, state).callEvent()) {
            return false;
        }
        state.update(true, false);
        return true;
    }

    private boolean isHarvestable(Block block) {
        if (block == null || !CROPS.containsKey(block.getType())
                || !Bukkit.isOwnedByCurrentRegion(block)
                || !block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
            return false;
        }
        return block.getBlockData() instanceof Ageable crop && crop.getAge() >= crop.getMaximumAge();
    }

    private static void resetAge(Block block) {
        if (block.getBlockData() instanceof Ageable crop) {
            crop.setAge(0);
            block.setBlockData(crop);
        }
    }

    private static void removeOneSeed(Collection<ItemStack> drops, Material seed) {
        if (seed == null) {
            return;
        }
        for (ItemStack drop : drops) {
            if (drop != null && drop.getType() == seed && drop.getAmount() > 0) {
                drop.setAmount(drop.getAmount() - 1);
                return;
            }
        }
    }

    private void spawnXp(Block block, int amount) {
        Location location = block.getLocation().add(0.5, 0.5, 0.5);
        block.getWorld().spawn(location, ExperienceOrb.class, orb -> orb.setExperience(amount));
    }

    private static boolean isHoe(ItemStack item) {
        return item != null && Tag.ITEMS_HOES.isTagged(item.getType());
    }

    private static boolean isDamageable(ItemStack tool) {
        return tool != null && !tool.getType().isAir() && tool.getType().getMaxDurability() > 0;
    }

    private ItemStack damage(Player player, ItemStack tool) {
        ItemStack result = tool.damage(1, player);
        player.getInventory().setItemInMainHand(result);
        return result;
    }

    private static boolean survivalLike(Player player) {
        GameMode mode = player.getGameMode();
        return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
    }

    private void write(String key, Object value) {
        plugin.getConfig().set(PATH + key, value);
        plugin.saveConfig();
    }

    private static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }

    private static Map<Material, Material> crops() {
        Map<Material, Material> map = new LinkedHashMap<>();
        map.put(Material.WHEAT, Material.WHEAT_SEEDS);
        map.put(Material.CARROTS, Material.CARROT);
        map.put(Material.POTATOES, Material.POTATO);
        map.put(Material.BEETROOTS, Material.BEETROOT_SEEDS);
        map.put(Material.NETHER_WART, Material.NETHER_WART);
        return map;
    }
}
