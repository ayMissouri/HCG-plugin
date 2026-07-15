package dev.amissouri.hcg.lavaraise;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.amissouri.hcg.AsyncSaver;
import dev.amissouri.hcg.HcgScheduler;
import dev.amissouri.hcg.LoadedChunks;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Remembers burnable blocks placed by players in the event world, so the rising lava can burn player builds
 */
public final class LavaBurnTracker implements Listener {

    private static final long SAVE_PERIOD_TICKS = 6000L;

    private final JavaPlugin plugin;
    private final HcgScheduler scheduler;
    private final LavaRaiseManager manager;

    private final Map<Long, Map<Integer, Set<Long>>> byChunk = new ConcurrentHashMap<>();
    private final AsyncSaver<byte[]> saver;

    public LavaBurnTracker(JavaPlugin plugin, HcgScheduler scheduler, LavaRaiseManager manager) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.manager = manager;
        load();
        this.saver = new AsyncSaver<>(scheduler, SAVE_PERIOD_TICKS, this::snapshot, this::writeBytes);
        this.saver.start();
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (!block.getType().isBurnable() || !block.getWorld().equals(manager.eventWorld())) {
            return;
        }
        if (manager.isBlockInLava(block)) {
            // Placed straight into the lava, burns immediately.
            scheduler.region(block.getLocation(), () -> {
                if (block.getType().isBurnable() && manager.isBlockInLava(block)) {
                    burn(block);
                }
            });
            return;
        }
        levels(chunkKeyOf(block)).computeIfAbsent(block.getY(), k -> ConcurrentHashMap.newKeySet())
                .add(pack(block.getX(), block.getZ()));
        saver.markDirty();
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Map<Integer, Set<Long>> levels = byChunk.get(chunkKeyOf(block));
        if (levels == null) {
            return;
        }
        Set<Long> set = levels.get(block.getY());
        if (set != null && set.remove(pack(block.getX(), block.getZ()))) {
            saver.markDirty();
        }
    }

    private static long chunkKeyOf(Block block) {
        return LoadedChunks.key(block.getX() >> 4, block.getZ() >> 4);
    }

    private Map<Integer, Set<Long>> levels(long chunkKey) {
        return byChunk.computeIfAbsent(chunkKey, k -> new ConcurrentHashMap<>());
    }

    public void burnRange(int fromY, int toY) {
        World world = manager.eventWorld();
        for (Map.Entry<Long, Map<Integer, Set<Long>>> chunk : byChunk.entrySet()) {
            int chunkX = LoadedChunks.chunkX(chunk.getKey());
            int chunkZ = LoadedChunks.chunkZ(chunk.getKey());
            Map<Integer, Set<Long>> levels = chunk.getValue();
            if (!hasAnyIn(levels, fromY, toY)) {
                continue;
            }
            scheduler.region(world, chunkX, chunkZ, () -> burnChunk(world, chunkX, chunkZ, levels, fromY, toY));
        }
    }

    private static boolean hasAnyIn(Map<Integer, Set<Long>> levels, int fromY, int toY) {
        for (int y = fromY; y <= toY; y++) {
            Set<Long> set = levels.get(y);
            if (set != null && !set.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void burnChunk(World world, int chunkX, int chunkZ,
            Map<Integer, Set<Long>> levels, int fromY, int toY) {
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return;
        }
        boolean removedAny = false;
        for (int y = fromY; y <= toY; y++) {
            Set<Long> set = levels.get(y);
            if (set == null || set.isEmpty()) {
                continue;
            }
            Iterator<Long> iterator = set.iterator();
            while (iterator.hasNext()) {
                long key = iterator.next();
                int x = (int) (key >> 32);
                int z = (int) key;
                if (!manager.inRegionXZ(x, z)) {
                    continue; // outside the event, keep tracking it
                }
                Block block = world.getBlockAt(x, y, z);
                if (block.getType().isBurnable()) {
                    burn(block);
                }
                iterator.remove();
                removedAny = true;
            }
        }
        if (removedAny) {
            saver.markDirty();
        }
    }

    private void burn(Block block) {
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        block.getWorld().spawnParticle(Particle.LAVA, center, 10, 0.3, 0.3, 0.3);
        block.getWorld().playSound(center, Sound.BLOCK_FIRE_EXTINGUISH, 0.6f, 1.0f);
        block.setType(Material.AIR, false);
    }

    private File dataFile() {
        return new File(plugin.getDataFolder(), "placed-burnables.dat");
    }

    public void shutdown() {
        saver.flushNow();
    }

    private byte[] snapshot() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        List<int[]> positions = new ArrayList<>();
        for (Map<Integer, Set<Long>> levels : byChunk.values()) {
            for (Map.Entry<Integer, Set<Long>> entry : levels.entrySet()) {
                for (long key : entry.getValue()) {
                    positions.add(new int[]{(int) (key >> 32), entry.getKey(), (int) key});
                }
            }
        }
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(positions.size());
            for (int[] position : positions) {
                out.writeInt(position[0]);
                out.writeInt(position[1]);
                out.writeInt(position[2]);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    private void writeBytes(byte[] data) {
        try {
            Path path = dataFile().toPath();
            Files.createDirectories(path.getParent());
            Files.write(path, data);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save placed-burnables.dat: " + e.getMessage());
        }
    }

    private void load() {
        File file = dataFile();
        if (!file.exists()) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int x = in.readInt();
                int y = in.readInt();
                int z = in.readInt();
                levels(LoadedChunks.key(x >> 4, z >> 4))
                        .computeIfAbsent(y, k -> ConcurrentHashMap.newKeySet())
                        .add(pack(x, z));
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not load placed-burnables.dat: " + e.getMessage());
        }
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
