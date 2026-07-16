package dev.amissouri.hcg.tweaks;

import dev.amissouri.hcg.HcgScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class PathSprintListener implements Listener {

    private static final long PERIOD_TICKS = 5L;
    private static final int BOOST_TICKS = 15;

    private final PathSprintTweak tweak;
    private final HcgScheduler scheduler;

    public PathSprintListener(PathSprintTweak tweak, HcgScheduler scheduler) {
        this.tweak = tweak;
        this.scheduler = scheduler;
    }

    public void startAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            start(player);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        start(event.getPlayer());
    }

    private void start(Player player) {
        scheduler.entityTimer(player, () -> tick(player), PERIOD_TICKS, PERIOD_TICKS);
    }

    private void tick(Player player) {
        if (!tweak.isEnabled() || !player.hasPermission("hcg.pathsprint.use")) {
            return;
        }
        if (tweak.requireSprint() && !player.isSprinting()) {
            return;
        }
        if (!onPath(player)) {
            return;
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, BOOST_TICKS,
                tweak.speed() - 1, false, tweak.particles(), false));
    }

    private static boolean onPath(Player player) {
        return player.getLocation().getBlock().getType() == Material.DIRT_PATH;
    }
}
