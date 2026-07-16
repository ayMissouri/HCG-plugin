package dev.amissouri.hcg.tweaks;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.amissouri.hcg.HcgPlatform;
import dev.amissouri.hcg.HcgScheduler;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

public final class MobHealthListener implements Listener {

    private static final long PERIOD_TICKS = 5L;

    private static final class Bar {

        private final TextDisplay display;
        private final Set<UUID> viewers = ConcurrentHashMap.newKeySet();

        private Bar(TextDisplay display) {
            this.display = display;
        }
    }

    private final Plugin plugin;
    private final MobHealthTweak tweak;
    private final HcgScheduler scheduler;
    private final Map<UUID, Bar> bars = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> watching = new ConcurrentHashMap<>();
    private final Set<UUID> actionBars = ConcurrentHashMap.newKeySet();

    public MobHealthListener(Plugin plugin, MobHealthTweak tweak, HcgScheduler scheduler) {
        this.plugin = plugin;
        this.tweak = tweak;
        this.scheduler = scheduler;
    }

    public void startAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            start(player);
        }
    }

    private void start(Player player) {
        scheduler.entityTimer(player, () -> tick(player), PERIOD_TICKS, PERIOD_TICKS);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        start(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        actionBars.remove(playerId);
        UUID mobId = watching.remove(playerId);
        if (mobId != null) {
            release(mobId, playerId, null);
        }
    }

    @EventHandler
    public void onRemove(EntityRemoveEvent event) {
        Bar bar = bars.remove(event.getEntity().getUniqueId());
        if (bar != null && event.getCause() != EntityRemoveEvent.Cause.UNLOAD) {
            remove(bar.display);
        }
    }

    public void shutdown() {
        if (!HcgPlatform.isFolia()) {
            for (Bar bar : bars.values()) {
                bar.display.remove();
            }
        }
        bars.clear();
        watching.clear();
        actionBars.clear();
    }

    private void tick(Player player) {
        LivingEntity target = tweak.isEnabled() && player.hasPermission("hcg.mobhealth.use")
                ? findTarget(player) : null;
        boolean aboveMob = target != null && tweak.display() == MobHealthTweak.Display.ABOVE_MOB;
        if (!aboveMob) {
            stopWatching(player);
        }
        if (target == null || aboveMob) {
            if (actionBars.remove(player.getUniqueId())) {
                player.sendActionBar(Component.empty());
            }
        }
        if (target == null) {
            return;
        }
        if (aboveMob) {
            showBar(player, target);
        } else {
            actionBars.add(player.getUniqueId());
            player.sendActionBar(tweak.actionBarText(target));
        }
    }

    private LivingEntity findTarget(Player player) {
        Entity hit = player.getTargetEntity(tweak.range(), false);
        if (!(hit instanceof LivingEntity living) || living.isDead() || living.isInvisible()
                || living instanceof ArmorStand || !Bukkit.isOwnedByCurrentRegion(living)) {
            return null;
        }
        if (living instanceof Player other && (!tweak.includePlayers()
                || other.getGameMode() == GameMode.SPECTATOR || !player.canSee(other))) {
            return null;
        }
        return living;
    }

    private void showBar(Player player, LivingEntity mob) {
        UUID mobId = mob.getUniqueId();
        UUID playerId = player.getUniqueId();
        Bar bar = bars.get(mobId);
        if (bar != null && (!bar.display.isValid() || bar.display.getVehicle() == null)) {
            bars.remove(mobId, bar);
            remove(bar.display);
            bar = null;
        }
        if (bar == null) {
            bar = new Bar(spawnBar(mob));
            bars.put(mobId, bar);
        }
        UUID previous = watching.put(playerId, mobId);
        if (previous != null && !previous.equals(mobId)) {
            release(previous, playerId, player);
        }
        if (bar.viewers.add(playerId)) {
            player.showEntity(plugin, bar.display);
        }
        bar.display.text(tweak.barText(mob));
    }

    private void stopWatching(Player player) {
        UUID mobId = watching.remove(player.getUniqueId());
        if (mobId != null) {
            release(mobId, player.getUniqueId(), player);
        }
    }

    private void release(UUID mobId, UUID playerId, Player player) {
        Bar bar = bars.get(mobId);
        if (bar == null) {
            return;
        }
        bar.viewers.remove(playerId);
        if (player != null && bar.display.isValid()
                && Bukkit.isOwnedByCurrentRegion(bar.display)) {
            player.hideEntity(plugin, bar.display);
        }
        if (bar.viewers.isEmpty() && bars.remove(mobId, bar)) {
            remove(bar.display);
        }
    }

    private TextDisplay spawnBar(LivingEntity mob) {
        TextDisplay display = mob.getWorld().spawn(mob.getLocation(), TextDisplay.class, spawned -> {
            spawned.setPersistent(false);
            spawned.setVisibleByDefault(false);
            spawned.setBillboard(Display.Billboard.CENTER);
            spawned.setShadowed(false);
            spawned.setTransformation(new Transformation(
                    new Vector3f(0.0f, 0.4f, 0.0f), new AxisAngle4f(),
                    new Vector3f(1.0f, 1.0f, 1.0f), new AxisAngle4f()));
        });
        mob.addPassenger(display);
        return display;
    }

    private void remove(TextDisplay display) {
        if (!display.isValid()) {
            return;
        }
        if (Bukkit.isOwnedByCurrentRegion(display)) {
            display.remove();
        } else {
            scheduler.entity(display, display::remove);
        }
    }
}
