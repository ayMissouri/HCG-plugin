package dev.amissouri.hcg.firstto;
import dev.amissouri.hcg.HcgScheduler;
import dev.amissouri.hcg.Messages;
import dev.amissouri.hcg.Players;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

/**
 * First-to race rounds: a chest GUI rolls through items like a slot machine, lands on a random
 * target, and the first player to craft it (craft mode) or hold it (obtain mode) wins.
 */
public final class FirstToManager {

    public enum Mode { CRAFT, OBTAIN }

    /** Marks the rolling GUI so the listener can cancel clicks inside it. */
    static final class RollHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final Title.Times TITLE_TIMES =
            Title.Times.times(Duration.ZERO, Duration.ofMillis(1200), Duration.ofMillis(300));
    private static final int CENTER_SLOT = 13;
    private static final int[] ROLL_DELAYS = {
            2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 5, 5, 6, 7, 8, 9, 10, 12};
    private static final int SCAN_INTERVAL_TICKS = 10;
    private static final int CLOSE_DELAY_TICKS = 60;

    /** Items that exist as items but can't be obtained in survival. */
    private static final Set<Material> UNOBTAINABLE = EnumSet.of(
            Material.BEDROCK, Material.BARRIER, Material.LIGHT, Material.SPAWNER,
            Material.TRIAL_SPAWNER, Material.VAULT, Material.END_PORTAL_FRAME,
            Material.STRUCTURE_BLOCK, Material.STRUCTURE_VOID, Material.JIGSAW,
            Material.DEBUG_STICK, Material.KNOWLEDGE_BOOK, Material.FARMLAND,
            Material.DIRT_PATH, Material.REINFORCED_DEEPSLATE, Material.BUDDING_AMETHYST,
            Material.CHORUS_PLANT, Material.FROGSPAWN, Material.PETRIFIED_OAK_SLAB,
            Material.PLAYER_HEAD, Material.BUNDLE, Material.SUSPICIOUS_SAND,
            Material.SUSPICIOUS_GRAVEL, Material.GLOBE_BANNER_PATTERN);

    /** Nether-gated items whose material name doesn't give them away. */
    private static final Set<Material> NETHER_EXTRA = EnumSet.of(
            Material.GHAST_TEAR, Material.BREWING_STAND, Material.BEACON, Material.LODESTONE,
            Material.ANCIENT_DEBRIS, Material.RESPAWN_ANCHOR, Material.WITHER_ROSE,
            Material.WITHER_SKELETON_SKULL, Material.PIGLIN_HEAD, Material.END_CRYSTAL,
            Material.ENDER_CHEST, Material.ENDER_EYE, Material.TWISTING_VINES,
            Material.WEEPING_VINES, Material.MUSIC_DISC_PIGSTEP,
            Material.PIGLIN_BANNER_PATTERN, Material.RIB_ARMOR_TRIM_SMITHING_TEMPLATE,
            Material.SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE);

    /** End-gated items whose material name doesn't give them away. */
    private static final Set<Material> END_EXTRA = EnumSet.of(
            Material.END_ROD, Material.LINGERING_POTION, Material.TIPPED_ARROW,
            Material.SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE);

    private final JavaPlugin plugin;
    private final HcgScheduler scheduler;
    private final Random random = new Random();
    private volatile boolean includeNether;
    private volatile boolean includeEnd;
    private volatile boolean tpSpawnOnWin;

    private final Map<UUID, RollHolder> guis = new ConcurrentHashMap<>();
    private final AtomicBoolean winClaimed = new AtomicBoolean();

    private volatile Mode mode;
    private volatile Material target;
    private volatile boolean rolling;
    private volatile boolean active;
    private volatile List<Material> pool;
    private volatile ScheduledTask rollTask;
    private volatile ScheduledTask scanTask;
    private volatile long startMillis;
    private volatile int round;

    public FirstToManager(JavaPlugin plugin, HcgScheduler scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.includeNether = plugin.getConfig().getBoolean("first-to.include-nether", true);
        this.includeEnd = plugin.getConfig().getBoolean("first-to.include-end", true);
        this.tpSpawnOnWin = plugin.getConfig().getBoolean("first-to.tp-spawn-on-win", false);
    }

    public boolean isRunning() {
        return rolling || active;
    }

    public boolean isRolling() {
        return rolling;
    }

    public Mode mode() {
        return mode;
    }

    public Material target() {
        return target;
    }

    public boolean includeNether() {
        return includeNether;
    }

    public boolean includeEnd() {
        return includeEnd;
    }

    public boolean tpSpawnOnWin() {
        return tpSpawnOnWin;
    }

    public void setIncludeNether(boolean value) {
        includeNether = value;
        plugin.getConfig().set("first-to.include-nether", value);
        plugin.saveConfig();
    }

    public void setIncludeEnd(boolean value) {
        includeEnd = value;
        plugin.getConfig().set("first-to.include-end", value);
        plugin.saveConfig();
    }

    public void setTpSpawnOnWin(boolean value) {
        tpSpawnOnWin = value;
        plugin.getConfig().set("first-to.tp-spawn-on-win", value);
        plugin.saveConfig();
    }

    /** Picks a target from the mode's pool and starts the slot-machine roll. False if the pool is empty. */
    public boolean start(Mode mode) {
        List<Material> built = buildPool(mode);
        if (built.isEmpty()) {
            return false;
        }
        scheduler.global(() -> {
            if (rolling || active) {
                return;
            }
            round++;
            this.pool = built;
            this.mode = mode;
            this.target = built.get(random.nextInt(built.size()));
            winClaimed.set(false);
            rolling = true;
            openGuis();
            Messages.broadcast("firstto.rolling-broadcast");

            rollTask = scheduler.globalTimer(new Runnable() {
                private int tick;
                private int nextSwitch;
                private int switchIndex;

                @Override
                public void run() {
                    if (tick++ < nextSwitch) {
                        return;
                    }
                    if (switchIndex >= ROLL_DELAYS.length) {
                        finishRoll();
                        return;
                    }
                    setCenterItem(built.get(random.nextInt(built.size())));
                    float pitch = 0.8f + 1.0f * switchIndex / ROLL_DELAYS.length;
                    playSound(Sound.BLOCK_NOTE_BLOCK_HAT, pitch);
                    nextSwitch = tick + ROLL_DELAYS[switchIndex++];
                }
            }, 1, 1);
        });
        return true;
    }

    private void openGuis() {
        guis.clear();
        Component title = Messages.msg("firstto.gui-title");
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        Players.forEachOnline(scheduler, player -> {
            RollHolder holder = new RollHolder();
            holder.inventory = Bukkit.createInventory(holder, 27, title);
            for (int slot = 0; slot < 27; slot++) {
                holder.inventory.setItem(slot, filler);
            }
            holder.inventory.setItem(CENTER_SLOT - 9, new ItemStack(Material.YELLOW_STAINED_GLASS_PANE));
            holder.inventory.setItem(CENTER_SLOT + 9, new ItemStack(Material.YELLOW_STAINED_GLASS_PANE));
            guis.put(player.getUniqueId(), holder);
            player.openInventory(holder.inventory);
        });
    }

    private void setCenterItem(Material material) {
        Players.forEachOnline(scheduler, player -> {
            RollHolder holder = guis.get(player.getUniqueId());
            if (holder != null) {
                holder.inventory.setItem(CENTER_SLOT, new ItemStack(material));
            }
        });
    }

    private void finishRoll() {
        cancelRollTask();
        rolling = false;
        active = true;
        startMillis = System.currentTimeMillis();
        setCenterItem(target);
        playSound(Sound.ENTITY_PLAYER_LEVELUP, 1f);

        Messages.broadcast(mode == Mode.CRAFT
                ? "firstto.craft-target-broadcast" : "firstto.obtain-target-broadcast",
                "item", prettyName(target));

        int myRound = round;
        scheduler.globalDelayed(() -> {
            if (round == myRound) {
                closeGuis();
            }
        }, CLOSE_DELAY_TICKS);

        if (mode == Mode.OBTAIN) {
            scanTask = scheduler.globalTimer(this::scanInventories,
                    SCAN_INTERVAL_TICKS, SCAN_INTERVAL_TICKS);
        }
    }

    /** Reopens the rolling GUI next tick so players can't close it while an item is being picked. */
    void handleGuiClose(Player player, Inventory inventory) {
        RollHolder holder = guis.get(player.getUniqueId());
        if (!rolling || holder == null || inventory != holder.inventory) {
            return;
        }
        scheduler.entity(player, () -> {
            RollHolder current = guis.get(player.getUniqueId());
            if (rolling && current != null && player.isOnline()) {
                player.openInventory(current.inventory);
            }
        });
    }

    void handleCraft(Player player, Material crafted) {
        if (active && mode == Mode.CRAFT && crafted == target && isEligible(player)) {
            tryWin(player);
        }
    }

    private void scanInventories() {
        Material wanted = target;
        if (wanted == null) {
            return;
        }
        Players.forEachOnline(scheduler, player -> {
            if (!isEligible(player)) {
                return;
            }
            if (player.getInventory().contains(wanted) || player.getItemOnCursor().getType() == wanted) {
                tryWin(player);
            }
        });
    }

    private boolean isEligible(Player player) {
        return player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE;
    }

    private void tryWin(Player winner) {
        if (!active || !winClaimed.compareAndSet(false, true)) {
            return;
        }
        String winnerName = winner.getName();
        scheduler.global(() -> winNow(winnerName));
    }

    private void winNow(String winnerName) {
        Material won = target;
        if (!active || won == null) {
            return;
        }
        String item = prettyName(won);
        String action = Messages.raw(actionKey());
        String time = formatDuration(System.currentTimeMillis() - startMillis);
        boolean teleport = tpSpawnOnWin;
        clearRound();
        Messages.broadcast("firstto.winner-broadcast",
                "player", winnerName, "action", action, "item", item, "time", time);
        showTitle("firstto.winner-title", "firstto.winner-subtitle",
                "player", winnerName, "action", action, "item", item, "time", time);
        playSound(Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f);
        if (teleport) {
            Location spawn = Bukkit.getWorlds().get(0).getSpawnLocation();
            Players.forEachOnline(scheduler, player -> player.teleportAsync(spawn));
            Messages.broadcast("firstto.tp-broadcast");
        }
    }

    public void stop() {
        scheduler.global(this::clearRound);
    }

    public void shutdown() {
        cancelRollTask();
        HcgScheduler.cancel(scanTask);
        scanTask = null;
        rolling = false;
        active = false;
        for (UUID id : guis.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                try {
                    player.closeInventory();
                } catch (Throwable ignored) {}
            }
        }
        guis.clear();
    }

    private void clearRound() {
        round++;
        cancelRollTask();
        HcgScheduler.cancel(scanTask);
        scanTask = null;
        rolling = false;
        active = false;
        mode = null;
        target = null;
        pool = null;
        closeGuis();
    }

    private void cancelRollTask() {
        HcgScheduler.cancel(rollTask);
        rollTask = null;
    }

    private void closeGuis() {
        List<UUID> ids = List.copyOf(guis.keySet());
        guis.clear();
        for (UUID id : ids) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                scheduler.entity(player, player::closeInventory);
            }
        }
    }

    private String actionKey() {
        return mode == Mode.CRAFT ? "firstto.action-craft" : "firstto.action-obtain";
    }

    private List<Material> buildPool(Mode mode) {
        return mode == Mode.CRAFT ? buildCraftPool() : buildObtainPool();
    }

    private List<Material> buildCraftPool() {
        EnumSet<Material> results = EnumSet.noneOf(Material.class);
        Iterator<Recipe> recipes = Bukkit.recipeIterator();
        while (recipes.hasNext()) {
            Recipe recipe = recipes.next();
            if (recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe) {
                Material result = recipe.getResult().getType();
                if (allowed(result)) {
                    results.add(result);
                }
            }
        }
        return new ArrayList<>(results);
    }

    private List<Material> buildObtainPool() {
        List<Material> items = new ArrayList<>();
        for (Material material : Material.values()) {
            if (!material.isLegacy() && material.isItem() && !material.isAir() && allowed(material)) {
                items.add(material);
            }
        }
        return items;
    }

    private boolean allowed(Material material) {
        if (isUnobtainable(material)) {
            return false;
        }
        if (!includeNether && isNether(material)) {
            return false;
        }
        return includeEnd || !isEnd(material);
    }

    private static boolean isUnobtainable(Material material) {
        String name = material.name();
        return UNOBTAINABLE.contains(material)
                || name.endsWith("_SPAWN_EGG")
                || name.startsWith("INFESTED_")
                || name.contains("COMMAND_BLOCK");
    }

    private static boolean isNether(Material material) {
        String name = material.name();
        return NETHER_EXTRA.contains(material)
                || name.contains("NETHER")
                || name.startsWith("CRIMSON_")
                || name.startsWith("WARPED_")
                || name.startsWith("SOUL_")
                || name.contains("BLACKSTONE")
                || name.contains("BASALT")
                || name.contains("QUARTZ")
                || name.contains("GLOWSTONE")
                || name.contains("SHROOMLIGHT")
                || name.contains("MAGMA")
                || name.contains("BLAZE");
    }

    private static boolean isEnd(Material material) {
        String name = material.name();
        return END_EXTRA.contains(material)
                || name.contains("END_STONE")
                || name.contains("PURPUR")
                || name.contains("CHORUS")
                || name.contains("SHULKER")
                || name.contains("DRAGON")
                || name.contains("ELYTRA");
    }

    static String formatDuration(long millis) {
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m " + seconds + "s";
        }
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }

    public static String prettyName(Material material) {
        String[] words = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder pretty = new StringBuilder();
        for (String word : words) {
            if (!pretty.isEmpty()) {
                pretty.append(' ');
            }
            pretty.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return pretty.toString();
    }

    /** Titles are a packet send and touch no region-owned state, so no fan-out needed. */
    private void showTitle(String titleKey, String subtitleKey, String... pairs) {
        Title title = Title.title(Messages.msg(titleKey, pairs), Messages.msg(subtitleKey, pairs), TITLE_TIMES);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(title);
        }
    }

    /** Unlike titles, this reads each player's own location, so it belongs on their region. */
    private void playSound(Sound sound, float pitch) {
        Players.forEachOnline(scheduler, player -> player.playSound(player.getLocation(), sound, 1f, pitch));
    }
}
