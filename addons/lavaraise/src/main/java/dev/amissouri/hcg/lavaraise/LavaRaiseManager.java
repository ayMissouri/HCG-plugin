package dev.amissouri.hcg.lavaraise;
import dev.amissouri.hcg.AsyncSaver;
import dev.amissouri.hcg.HcgScheduler;
import dev.amissouri.hcg.LoadedChunks;
import dev.amissouri.hcg.Messages;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.papermc.paper.math.Position;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

public final class LavaRaiseManager {

    public enum Phase { IDLE, ARMED, RISING, HOLDING, DRAINING }

    record LavaState(Phase phase, UUID worldId, int currentLevel,
                     int minX, int maxX, int minZ, int maxZ, int minY, int topY,
                     int perPlayerLayerBudget) {

        static LavaState idle() {
            return new LavaState(Phase.IDLE, null, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        boolean isActive() {
            return phase == Phase.RISING || phase == Phase.HOLDING || phase == Phase.DRAINING;
        }

        boolean inRegionXZ(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    private static final class PlayerView {
        final Map<Long, Integer> rendered = new HashMap<>();
        final ArrayDeque<Job> jobs = new ArrayDeque<>();
        ScheduledTask task;
        volatile long ticks;
        long lastSeenTicks = -1;
        volatile long resetAtTick = -1;
    }

    private record Job(int chunkX, int chunkZ, int fromY, int toY, boolean render) {}

    private static final long VIEW_RESET_DELAY_TICKS = 10L;
    private static final long GAMEMODE_DELAY_TICKS = 2L;
    private static final int GAMEMODE_ATTEMPTS = 20;

    private final JavaPlugin plugin;
    private final HcgScheduler scheduler;
    private final BlockData lavaData = Material.LAVA.createBlockData();

    private volatile LavaState state = LavaState.idle();
    private final Map<UUID, PlayerView> views = new ConcurrentHashMap<>();

    private ScheduledTask task;
    private volatile World world;
    private volatile boolean fastDrain;

    private long tickCounter;
    private long phaseStartTick;
    private double ticksPerLayer;
    private int drainStartLevel;
    private boolean pendingDrain;
    private int burnedUpTo;
    private long prevDayTime = -1;
    private LavaBurnTracker burnTracker;

    private volatile boolean purging;
    private volatile int purgeTopY;
    private int purgeCursor;
    private long[] purgeChunks = new long[0];
    private final AtomicInteger purgeInFlight = new AtomicInteger();
    private final AtomicInteger purgeRemoved = new AtomicInteger();

    private final Map<UUID, GameMode> spectating = new ConcurrentHashMap<>();
    private final AsyncSaver<String> spectatorSaver;

    public LavaRaiseManager(JavaPlugin plugin, HcgScheduler scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        loadSpectators();
        this.spectatorSaver = new AsyncSaver<>(scheduler, 100L,
                this::snapshotSpectators, this::writeSpectators);
        this.spectatorSaver.start();
        if (plugin.getConfig().getBoolean("lava-raise.enabled", false)) {
            enable();
        }
    }

    public boolean spectateOnDeath() {
        return plugin.getConfig().getBoolean("lava-raise.spectate-on-death", true);
    }

    void handleRespawn(Player player) {
        if (!state.isActive() || !spectateOnDeath()) {
            return;
        }
        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) {
            return; // leave creative and existing spectators alone
        }
        spectating.putIfAbsent(player.getUniqueId(), mode);
        spectatorSaver.markDirty();
        setGameModeSoon(player.getUniqueId(), GameMode.SPECTATOR, GAMEMODE_ATTEMPTS);
    }

    private void setGameModeSoon(UUID id, GameMode mode, int attemptsLeft) {
        scheduler.globalDelayed(() -> {
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                return; // offline; the persisted entry restores them on rejoin
            }
            if (scheduler.entity(player, () -> player.setGameMode(mode)) != null) {
                return;
            }
            if (attemptsLeft > 0) {
                setGameModeSoon(id, mode, attemptsLeft - 1);
            } else {
                plugin.getLogger().warning("Could not set " + player.getName() + " to " + mode
                        + ": their entity never became schedulable.");
            }
        }, GAMEMODE_DELAY_TICKS);
    }

    void handleJoin(Player player) {
        GameMode mode = spectating.get(player.getUniqueId());
        if (mode != null && !state.isActive()) {
            spectating.remove(player.getUniqueId());
            spectatorSaver.markDirty();
            setGameModeSoon(player.getUniqueId(), mode, GAMEMODE_ATTEMPTS);
        }
    }

    private void restoreSpectators() {
        for (UUID id : List.copyOf(spectating.keySet())) {
            if (Bukkit.getPlayer(id) == null) {
                continue;
            }
            setGameModeSoon(id, spectating.remove(id), GAMEMODE_ATTEMPTS);
        }
        spectatorSaver.markDirty();
    }

    private java.io.File spectatorFile() {
        return new java.io.File(plugin.getDataFolder(), "spectating.txt");
    }

    private String snapshotSpectators() {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<UUID, GameMode> entry : spectating.entrySet()) {
            text.append(entry.getKey()).append('=').append(entry.getValue().name()).append('\n');
        }
        return text.toString();
    }

    private void writeSpectators(String text) {
        try {
            java.nio.file.Path path = spectatorFile().toPath();
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.writeString(path, text, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Could not save spectating.txt: " + e.getMessage());
        }
    }

    private void loadSpectators() {
        java.io.File file = spectatorFile();
        if (!file.exists()) {
            return;
        }
        try {
            for (String line : java.nio.file.Files.readAllLines(file.toPath(),
                    java.nio.charset.StandardCharsets.UTF_8)) {
                int split = line.indexOf('=');
                if (split <= 0) {
                    continue;
                }
                try {
                    spectating.put(UUID.fromString(line.substring(0, split)),
                            GameMode.valueOf(line.substring(split + 1)));
                } catch (IllegalArgumentException ignored) {}
            }
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Could not load spectating.txt: " + e.getMessage());
        }
    }

    public boolean isEnabled() {
        return state.phase() != Phase.IDLE;
    }

    public Phase phase() {
        return state.phase();
    }

    public boolean isActive() {
        return state.isActive();
    }

    public boolean isPurging() {
        return purging;
    }

    public int startTime() {
        return plugin.getConfig().getInt("lava-raise.start-time", 0);
    }

    public int endTime() {
        return plugin.getConfig().getInt("lava-raise.end-time", 12000);
    }

    public int riseDurationSeconds() {
        return plugin.getConfig().getInt("lava-raise.rise-duration-seconds", 300);
    }

    public int maxY() {
        return plugin.getConfig().getInt("lava-raise.max-y", 62);
    }

    public boolean replaceWater() {
        return plugin.getConfig().getBoolean("lava-raise.replace-water", false);
    }

    public boolean burnPlacedBlocks() {
        return plugin.getConfig().getBoolean("lava-raise.burn-placed-blocks", true);
    }

    public boolean damageMobs() {
        return plugin.getConfig().getBoolean("lava-raise.damage-mobs", false);
    }

    public LavaBurnTracker burnTracker() {
        if (burnTracker == null) {
            burnTracker = new LavaBurnTracker(plugin, scheduler, this);
        }
        return burnTracker;
    }

    public boolean isBlockInLava(Block block) {
        LavaState s = state;
        return s.isActive() && s.currentLevel() >= s.minY()
                && block.getWorld().getUID().equals(s.worldId())
                && block.getY() <= s.currentLevel()
                && s.inRegionXZ(block.getX(), block.getZ());
    }

    public boolean inRegionXZ(int x, int z) {
        return state.inRegionXZ(x, z);
    }

    private int positionsPerTick() {
        return Math.max(2000, plugin.getConfig().getInt("lava-raise.blocks-per-tick", 40000));
    }

    private int maxRegionSize() {
        return Math.max(16, plugin.getConfig().getInt("lava-raise.max-region", 512));
    }

    private int renderRadius() {
        // Default covers everything the client can see; the config can lower it.
        int configured = Math.max(2, plugin.getConfig().getInt("lava-raise.render-radius", 32));
        return Math.min(configured, world != null ? world.getViewDistance() : Bukkit.getViewDistance());
    }

    public void set(String key, Object value) {
        plugin.getConfig().set("lava-raise." + key, value);
        plugin.saveConfig();
    }

    public World eventWorld() {
        return Bukkit.getWorlds().get(0);
    }

    public void enable() {
        set("enabled", true);
        scheduler.global(() -> {
            if (state.phase() == Phase.IDLE) {
                state = new LavaState(Phase.ARMED, null, 0, 0, 0, 0, 0, 0, 0, 0);
                prevDayTime = -1;
                ensureTask();
            }
        });
    }

    public void disable() {
        set("enabled", false);
        scheduler.global(() -> {
            Phase phase = state.phase();
            if (phase == Phase.RISING || phase == Phase.HOLDING) {
                startDrain(true);
            } else if (phase == Phase.ARMED) {
                state = LavaState.idle();
                stopTaskIfIdle();
            }
        });
    }

    public void cancelEvent() {
        scheduler.global(() -> {
            Phase phase = state.phase();
            if (phase == Phase.RISING || phase == Phase.HOLDING) {
                startDrain(true);
            }
        });
    }

    /** @return false only when a purge or event is already running; the sweep itself starts next tick. */
    public boolean startPurge(int upToY) {
        if (purging || isActive()) {
            return false;
        }
        scheduler.global(() -> {
            if (purging || state.isActive()) {
                return;
            }
            world = eventWorld();
            state = snapshotRegion(state.phase());
            purging = true;
            purgeTopY = upToY;
            purgeRemoved.set(0);
            purgeInFlight.set(0);
            purgeCursor = 0;
            purgeChunks = purgeChunkGrid();
            ensureTask();
        });
        return true;
    }

    public void shutdown() {
        HcgScheduler.cancel(task);
        task = null;
        clearViews();
        for (UUID id : List.copyOf(spectating.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                continue;
            }
            GameMode mode = spectating.remove(id);
            try {
                player.setGameMode(mode);
            } catch (Throwable t) {
                plugin.getLogger().warning("Could not take " + player.getName()
                        + " out of spectator on shutdown: " + t);
            }
        }
        spectatorSaver.flushNow();
        state = LavaState.idle();
        purging = false;
        if (burnTracker != null) {
            burnTracker.shutdown();
        }
    }

    private void ensureTask() {
        if (task == null) {
            task = scheduler.globalTimer(this::tick, 1L, 1L);
        }
    }

    private void stopTaskIfIdle() {
        if (state.phase() == Phase.IDLE && !purging) {
            HcgScheduler.cancel(task);
            task = null;
        }
    }

    private void tick() {
        tickCounter++;
        Phase phase = state.phase();

        if (phase != Phase.IDLE) {
            World w = phase == Phase.ARMED ? eventWorld() : world;
            long dayTime = w.getTime();
            if (prevDayTime >= 0 && dayTime != prevDayTime) {
                if (phase == Phase.ARMED && crossed(startTime(), prevDayTime, dayTime)) {
                    startRise();
                } else if (phase == Phase.RISING && crossed(endTime(), prevDayTime, dayTime)) {
                    pendingDrain = true;
                } else if (phase == Phase.HOLDING && crossed(endTime(), prevDayTime, dayTime)) {
                    startDrain(false);
                }
            }
            prevDayTime = dayTime;
        }

        if (state.isActive()) {
            updateLevel();
            LavaState s = state;
            if (burnPlacedBlocks() && s.currentLevel() > burnedUpTo
                    && (s.phase() == Phase.RISING || s.phase() == Phase.HOLDING)) {
                burnTracker().burnRange(Math.max(s.minY(), burnedUpTo + 1), s.currentLevel());
                burnedUpTo = s.currentLevel();
            }
            if (tickCounter % 10 == 0) {
                syncViewTasks();
            }
            if (s.phase() == Phase.DRAINING && s.currentLevel() < s.minY() && viewsDrained()) {
                finishDrain();
            }
        }

        if (purging) {
            drivePurge();
        }
    }

    private boolean viewsDrained() {
        for (PlayerView view : views.values()) {
            synchronized (view) {
                if (!view.jobs.isEmpty() || !view.rendered.isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    static boolean crossed(int target, long prev, long now) {
        if (now >= prev) {
            return target > prev && target <= now;
        }
        return target > prev || target <= now;
    }

    public int currentLavaY() {
        LavaState s = state;
        return Math.max(s.minY(), s.currentLevel());
    }

    private void updateLevel() {
        LavaState s = state;
        switch (s.phase()) {
            case RISING -> {
                int level = s.minY() + (int) ((tickCounter - phaseStartTick) / ticksPerLayer);
                if (level >= s.topY()) {
                    publish(s.phase(), s.topY());
                    if (pendingDrain) {
                        startDrain(false);
                    } else {
                        publish(Phase.HOLDING, s.topY());
                        Messages.broadcastOps("lavaraise.reached-broadcast",
                                "y", String.valueOf(s.topY()));
                    }
                    return;
                }
                publish(s.phase(), level);
            }
            case HOLDING -> publish(Phase.HOLDING, s.topY());
            case DRAINING -> {
                int level = fastDrain
                        ? s.minY() - 1
                        : Math.max(s.minY() - 1,
                                drainStartLevel - (int) ((tickCounter - phaseStartTick) / ticksPerLayer));
                publish(Phase.DRAINING, level);
            }
            default -> { }
        }
    }

    /** Re-publishes the snapshot with a new phase/level. Driver only. */
    private void publish(Phase phase, int level) {
        LavaState s = state;
        state = new LavaState(phase, s.worldId(), level, s.minX(), s.maxX(), s.minZ(), s.maxZ(),
                s.minY(), s.topY(), perPlayerLayerBudget());
    }

    private int perPlayerLayerBudget() {
        int online = Math.max(1, Bukkit.getOnlinePlayers().size());
        return Math.max(8, positionsPerTick() / 256 / online);
    }

    private LavaState snapshotRegion(Phase phase) {
        WorldBorder border = world.getWorldBorder();
        int half = (int) Math.min(border.getSize(), maxRegionSize()) / 2;
        Location center = border.getCenter();
        int minY = world.getMinHeight() + 1;
        return new LavaState(phase, world.getUID(), state.currentLevel(),
                center.getBlockX() - half, center.getBlockX() + half,
                center.getBlockZ() - half, center.getBlockZ() + half,
                minY, Math.clamp(maxY(), minY + 1, world.getMaxHeight() - 1),
                perPlayerLayerBudget());
    }

    private void startRise() {
        world = eventWorld();
        LavaState s = snapshotRegion(Phase.RISING);
        ticksPerLayer = Math.max(0.05, riseDurationSeconds() * 20.0 / (s.topY() - s.minY() + 1));
        burnedUpTo = s.minY() - 1;
        phaseStartTick = tickCounter;
        pendingDrain = false;
        fastDrain = false;
        clearViews();
        state = new LavaState(Phase.RISING, s.worldId(), s.minY(), s.minX(), s.maxX(), s.minZ(), s.maxZ(),
                s.minY(), s.topY(), s.perPlayerLayerBudget());
        Messages.broadcastOps("lavaraise.rising-broadcast", "y", String.valueOf(s.topY()));
    }

    private void startDrain(boolean fast) {
        LavaState s = state;
        drainStartLevel = s.currentLevel();
        ticksPerLayer = Math.max(0.05,
                riseDurationSeconds() * 20.0 / Math.max(1, drainStartLevel - s.minY() + 1));
        phaseStartTick = tickCounter;
        fastDrain = fast;
        pendingDrain = false;
        publish(Phase.DRAINING, s.currentLevel());
        Messages.broadcastOps("lavaraise.receding-broadcast");
    }

    private void finishDrain() {
        clearViews();
        restoreSpectators();
        boolean stayEnabled = plugin.getConfig().getBoolean("lava-raise.enabled", false);
        state = stayEnabled
                ? new LavaState(Phase.ARMED, null, 0, 0, 0, 0, 0, 0, 0, 0)
                : LavaState.idle();
        Messages.broadcastOps(stayEnabled ? "lavaraise.gone-repeat-broadcast" : "lavaraise.gone-broadcast");
        stopTaskIfIdle();
    }

    private void clearViews() {
        for (PlayerView view : views.values()) {
            HcgScheduler.cancel(view.task);
            synchronized (view) {
                view.rendered.clear();
                view.jobs.clear();
            }
        }
        views.clear();
    }

    private void syncViewTasks() {
        views.keySet().removeIf(id -> {
            if (Bukkit.getPlayer(id) != null) {
                return false;
            }
            HcgScheduler.cancel(views.get(id).task);
            return true;
        });
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerView existing = views.get(player.getUniqueId());
            if (existing != null) {
                if (existing.ticks != existing.lastSeenTicks) {
                    existing.lastSeenTicks = existing.ticks;
                    continue;
                }
                HcgScheduler.cancel(existing.task);
                views.remove(player.getUniqueId());
            }
            PlayerView view = new PlayerView();
            ScheduledTask started = scheduler.entityTimer(player, () -> tickView(player, view), 1L, 1L);
            if (started == null) {
                continue;
            }
            view.task = started;
            views.put(player.getUniqueId(), view);
        }
    }

    void handleQuit(Player player) {
        dropView(player.getUniqueId());
    }

    void handleViewReset(Player player) {
        PlayerView view = views.get(player.getUniqueId());
        if (view != null) {
            view.resetAtTick = view.ticks + VIEW_RESET_DELAY_TICKS;
        }
    }

    private void dropView(UUID id) {
        PlayerView view = views.remove(id);
        if (view != null) {
            HcgScheduler.cancel(view.task);
        }
    }

    private void tickView(Player player, PlayerView view) {
        view.ticks++;
        LavaState s = state;
        if (!s.isActive() || !player.getWorld().getUID().equals(s.worldId())) {
            return;
        }
        if (view.resetAtTick >= 0 && view.ticks >= view.resetAtTick) {
            view.resetAtTick = -1;
            synchronized (view) {
                view.rendered.clear();
                view.jobs.clear();
            }
        }
        syncSelf(player, view, s);
        processJobs(player, view, s);
        if (view.ticks % 10 == 5) {
            burnSelf(player, s);
        }
    }

    private void syncSelf(Player player, PlayerView view, LavaState s) {
        int radius = renderRadius();
        int forgetBeyond = world.getViewDistance() + 2;
        int pcx = player.getLocation().getBlockX() >> 4;
        int pcz = player.getLocation().getBlockZ() >> 4;

        synchronized (view) {
            view.rendered.keySet().removeIf(key -> Math.abs(LoadedChunks.chunkX(key) - pcx) > forgetBeyond
                    || Math.abs(LoadedChunks.chunkZ(key) - pcz) > forgetBeyond);

            if (s.currentLevel() >= s.minY()) {
                int chunkMinX = Math.max(s.minX() >> 4, pcx - radius);
                int chunkMaxX = Math.min(s.maxX() >> 4, pcx + radius);
                int chunkMinZ = Math.max(s.minZ() >> 4, pcz - radius);
                int chunkMaxZ = Math.min(s.maxZ() >> 4, pcz + radius);
                for (int cx = chunkMinX; cx <= chunkMaxX; cx++) {
                    for (int cz = chunkMinZ; cz <= chunkMaxZ; cz++) {
                        view.rendered.putIfAbsent(LoadedChunks.key(cx, cz), s.minY() - 1);
                    }
                }
            }

            var iterator = view.rendered.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                int cx = LoadedChunks.chunkX(entry.getKey());
                int cz = LoadedChunks.chunkZ(entry.getKey());
                int renderedTo = entry.getValue();
                if (renderedTo < s.currentLevel()) {
                    view.jobs.add(new Job(cx, cz, renderedTo + 1, s.currentLevel(), true));
                    entry.setValue(s.currentLevel());
                } else if (renderedTo > s.currentLevel()) {
                    view.jobs.add(new Job(cx, cz, Math.max(s.minY(), s.currentLevel() + 1), renderedTo, false));
                    if (s.currentLevel() < s.minY()) {
                        iterator.remove();
                    } else {
                        entry.setValue(s.currentLevel());
                    }
                }
            }
        }
    }

    private void processJobs(Player player, PlayerView view, LavaState s) {
        int budget = s.perPlayerLayerBudget() * (fastDrain ? 4 : 1);
        while (budget > 0) {
            Job job;
            synchronized (view) {
                job = view.jobs.poll();
            }
            if (job == null) {
                return;
            }
            int layers = job.toY() - job.fromY() + 1;
            if (layers > budget) {
                int toY = job.render() ? job.fromY() + budget - 1 : job.toY();
                int fromY = job.render() ? job.fromY() : job.toY() - budget + 1;
                Job now = new Job(job.chunkX(), job.chunkZ(), fromY, toY, job.render());
                Job rest = job.render()
                        ? new Job(job.chunkX(), job.chunkZ(), toY + 1, job.toY(), true)
                        : new Job(job.chunkX(), job.chunkZ(), job.fromY(), fromY - 1, false);
                synchronized (view) {
                    view.jobs.addFirst(rest);
                }
                sendRange(player, now, s);
                return;
            }
            budget -= layers;
            sendRange(player, job, s);
        }
    }

    private void sendRange(Player player, Job job, LavaState s) {
        if (Bukkit.isOwnedByCurrentRegion(world, job.chunkX(), job.chunkZ())) {
            buildAndSend(player, job, s);
            return;
        }
        scheduler.region(world, job.chunkX(), job.chunkZ(), () -> buildAndSend(player, job, s));
    }

    private void buildAndSend(Player player, Job job, LavaState s) {
        if (!world.isChunkLoaded(job.chunkX(), job.chunkZ())) {
            return;
        }
        Map<Position, BlockData> changes = new HashMap<>();
        int baseX = job.chunkX() << 4;
        int baseZ = job.chunkZ() << 4;
        boolean water = replaceWater();
        for (int y = job.fromY(); y <= job.toY(); y++) {
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    int x = baseX + dx;
                    int z = baseZ + dz;
                    if (!s.inRegionXZ(x, z)) {
                        continue;
                    }
                    Block block = world.getBlockAt(x, y, z);
                    Material type = block.getType();
                    if (type.isAir() || (water && type == Material.WATER)) {
                        changes.put(Position.block(x, y, z), job.render() ? lavaData : block.getBlockData());
                    }
                }
            }
        }
        if (!changes.isEmpty()) {
            player.sendMultiBlockChange(changes);
        }
    }

    private void burnSelf(Player player, LavaState s) {
        if (s.currentLevel() < s.minY()) {
            return;
        }
        if ((player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE)
                && inLava(player.getLocation(), s) && !player.isInvulnerable()) {
            player.setFireTicks(Math.max(player.getFireTicks(), 100));
            player.damage(4.0);
        }
        if (damageMobs()) {
            burnMobsNearby(player, s);
        }
    }

    private void burnMobsNearby(Player player, LavaState s) {
        Chunk chunk = player.getLocation().getChunk();
        for (org.bukkit.entity.Entity entity : chunk.getEntities()) {
            if (!(entity instanceof LivingEntity living)
                    || entity instanceof Player || entity instanceof ArmorStand
                    || living.isInvulnerable()) {
                continue;
            }
            if (inLava(living.getLocation(), s)) {
                living.setFireTicks(Math.max(living.getFireTicks(), 100));
                living.damage(4.0);
            }
        }
    }

    private boolean inLava(Location loc, LavaState s) {
        if (loc.getBlockY() > s.currentLevel() || !s.inRegionXZ(loc.getBlockX(), loc.getBlockZ())) {
            return false;
        }
        return replaceWater() || loc.getBlock().getType() != Material.WATER;
    }

    private long[] purgeChunkGrid() {
        LavaState s = state;
        java.util.Set<Long> inBorder = new java.util.HashSet<>();
        for (int cx = s.minX() >> 4; cx <= s.maxX() >> 4; cx++) {
            for (int cz = s.minZ() >> 4; cz <= s.maxZ() >> 4; cz++) {
                inBorder.add(LoadedChunks.key(cx, cz));
            }
        }
        return java.util.Arrays.stream(LoadedChunks.snapshot(world))
                .filter(inBorder::contains)
                .toArray();
    }

    private void drivePurge() {
        int window = Math.max(1, plugin.getConfig().getInt("lava-raise.purge-chunks-in-flight", 6));
        while (purgeInFlight.get() < window && purgeCursor < purgeChunks.length) {
            long key = purgeChunks[purgeCursor++];
            int cx = LoadedChunks.chunkX(key);
            int cz = LoadedChunks.chunkZ(key);
            purgeInFlight.incrementAndGet();
            scheduler.region(world, cx, cz, () -> {
                try {
                    purgeChunk(cx, cz);
                } finally {
                    purgeInFlight.decrementAndGet();
                }
            });
        }
        if (purgeCursor >= purgeChunks.length && purgeInFlight.get() == 0) {
            purging = false;
            Messages.broadcastOps("lavaraise.purge-complete",
                    "count", String.valueOf(purgeRemoved.get()));
            stopTaskIfIdle();
        }
    }

    private void purgeChunk(int chunkX, int chunkZ) {
        LavaState s = state;
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return;
        }
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        int removed = 0;
        for (int y = world.getMinHeight(); y <= purgeTopY; y++) {
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    int x = baseX + dx;
                    int z = baseZ + dz;
                    if (!s.inRegionXZ(x, z)) {
                        continue;
                    }
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.LAVA) {
                        block.setType(Material.AIR, false);
                        removed++;
                    }
                }
            }
        }
        purgeRemoved.addAndGet(removed);
    }

    public static int parseTime(String input) {
        switch (input.toLowerCase()) {
            case "day", "morning" -> { return 1000; }
            case "noon" -> { return 6000; }
            case "sunset", "dusk" -> { return 12000; }
            case "night" -> { return 13000; }
            case "midnight" -> { return 18000; }
            case "sunrise", "dawn" -> { return 23000; }
            default -> { }
        }
        if (input.matches("\\d{1,2}:\\d{2}")) {
            String[] parts = input.split(":");
            int hours = Integer.parseInt(parts[0]);
            int minutes = Integer.parseInt(parts[1]);
            if (hours > 23 || minutes > 59) {
                return -1;
            }
            return (((hours - 6 + 24) % 24) * 1000) + (minutes * 1000 / 60);
        }
        try {
            int ticks = Integer.parseInt(input);
            return ticks >= 0 && ticks < 24000 ? ticks : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static String formatTime(int ticks) {
        int hours = (ticks / 1000 + 6) % 24;
        int minutes = (ticks % 1000) * 60 / 1000;
        return ticks + String.format(" (%02d:%02d)", hours, minutes);
    }
}
