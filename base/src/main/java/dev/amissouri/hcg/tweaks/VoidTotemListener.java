package dev.amissouri.hcg.tweaks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.amissouri.hcg.HcgScheduler;
import dev.amissouri.hcg.Messages;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.EntityEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class VoidTotemListener implements Listener {

    private static final long PERIOD_TICKS = 5L;
    private static final int LAND_SCAN = 3;

    private static final class Rescue {

        private final long start;
        private volatile ScheduledTask task;

        private Rescue(long start) {
            this.start = start;
        }
    }

    private final VoidTotemTweak tweak;
    private final HcgScheduler scheduler;
    private final Map<UUID, Rescue> active = new ConcurrentHashMap<>();

    public VoidTotemListener(VoidTotemTweak tweak, HcgScheduler scheduler) {
        this.tweak = tweak;
        this.scheduler = scheduler;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getCause() != DamageCause.VOID || !(event.getEntity() instanceof Player player)) {
            return;
        }
        if (active.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (!tweak.isEnabled() || !player.hasPermission("hcg.voidtotem.use") || !holdsTotem(player)) {
            return;
        }
        event.setCancelled(true);
        if (tweak.consume()) {
            consumeTotem(player);
        }
        if (tweak.effect()) {
            player.playEffect(EntityEffect.TOTEM_RESURRECT);
            player.playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 1.0f, 1.0f);
        }
        Messages.send(player, "tweaks.voidtotem.saved");
        start(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        finish(event.getPlayer().getUniqueId());
    }

    private void start(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION,
                (tweak.maxSeconds() + 2) * 20, tweak.level() - 1, false, true, true));
        Rescue rescue = new Rescue(System.currentTimeMillis());
        active.put(player.getUniqueId(), rescue);
        rescue.task = scheduler.entityTimer(player, () -> tick(player), PERIOD_TICKS, PERIOD_TICKS);
    }

    private void tick(Player player) {
        Rescue rescue = active.get(player.getUniqueId());
        if (rescue == null) {
            return;
        }
        if (!player.isOnline() || player.isDead()) {
            finish(player.getUniqueId());
            return;
        }
        boolean timedOut = System.currentTimeMillis() - rescue.start >= tweak.maxSeconds() * 1000L;
        if (backOnLand(player) || timedOut) {
            stopLevitation(player);
            finish(player.getUniqueId());
        }
    }

    private boolean backOnLand(Player player) {
        World world = player.getWorld();
        Location loc = player.getLocation();
        if (loc.getY() < world.getMinHeight()) {
            return false;
        }
        Block feet = loc.getBlock();
        for (int depth = 1; depth <= LAND_SCAN; depth++) {
            Block below = feet.getRelative(0, -depth, 0);
            if (below.getY() < world.getMinHeight()) {
                break;
            }
            if (below.getType().isSolid()) {
                return true;
            }
        }
        return false;
    }

    private void stopLevitation(Player player) {
        player.removePotionEffect(PotionEffectType.LEVITATION);
    }

    private void finish(UUID id) {
        Rescue rescue = active.remove(id);
        if (rescue != null) {
            HcgScheduler.cancel(rescue.task);
        }
    }

    private static boolean holdsTotem(Player player) {
        PlayerInventory inventory = player.getInventory();
        return inventory.getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || inventory.getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
    }

    private static void consumeTotem(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack main = inventory.getItemInMainHand();
        if (main.getType() == Material.TOTEM_OF_UNDYING) {
            main.setAmount(main.getAmount() - 1);
            inventory.setItemInMainHand(main);
            return;
        }
        ItemStack off = inventory.getItemInOffHand();
        if (off.getType() == Material.TOTEM_OF_UNDYING) {
            off.setAmount(off.getAmount() - 1);
            inventory.setItemInOffHand(off);
        }
    }
}
